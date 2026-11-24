package com.ziwei.zw.stream;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.agent.internal.AgentTaskManager;
import com.agentx.ai.core.interrupt.JdbcPauseStateStore;
import com.agentx.ai.core.memory.util.ChatHistorySelector;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.tools.BashTool;
import com.agentx.ai.core.tools.FileSystemTools;
import com.agentx.ai.core.tools.GrepTool;
import com.agentx.ai.core.tools.SkillsTool;
import com.agentx.ai.core.tools.TodoWriteTool;
import com.ziwei.zw.common.BizException;
import com.ziwei.zw.common.TenantContext;
import com.ziwei.zw.config.AgentConfig;
import com.ziwei.zw.config.ModelProvider;
import com.ziwei.zw.config.ZwProperties;
import com.ziwei.zw.rag.service.RagService;
import com.ziwei.zw.rag.tool.Citation;
import com.ziwei.zw.rag.tool.KbSearchTool;
import com.ziwei.zw.session.SessionService;
import com.ziwei.zw.skill.SkillManager;
import com.ziwei.zw.stream.dto.ChatRequest;
import com.ziwei.zw.stream.dto.CitationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 流式对话：组装工具 → 建 ReactAgent → 交给 {@link LiveStreamRegistry} 托管。
 *
 * <p>**没有智能体概念**了，平台就一个助手，所以工具是固定规则组装的：
 * <ul>
 *   <li>知识库 → {@code kbSearch}（用户有知识库就挂；请求里带了 knowledgeBaseId 就把检索范围收窄到那个库）；</li>
 *   <li>技能 → 框架 {@code SkillsTool}，**已启用的技能全部自动挂载**，不需要挑；</li>
 *   <li>任务追踪 → {@code TodoWrite}（yml 开关）；</li>
 *   <li>本地工具组 → 文件 / bash / grep（**默认关**，无沙箱裸跑很危险）。</li>
 * </ul>
 * 内置反问工具由 {@code askUser(true)} 注册，不在这里加。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamService {

    private final AgentConfig agentConfig;
    private final ModelProvider modelProvider;
    private final SkillManager skillManager;
    private final RagService ragService;
    private final LiveStreamRegistry registry;
    private final CitationStore citationStore;
    private final ChatHistorySelector chatHistorySelector;
    private final SessionService sessionService;
    private final DataSource dataSource;
    private final AgentTaskManager taskManager;
    private final JdbcPauseStateStore pauseStateStore;
    private final ZwProperties props;

    /** 工具装配结果：注册给 Agent 的工具 + 带引用收集的检索工具。 */
    private record ToolBundle(List<ToolCallback> tools, KbSearchTool searchTool) {
    }

    // ==================== 发起 ====================

    /** 发一轮。新会话时 conversationId 可空，服务端生成；追问时带上上一轮的 cid。 */
    public Flux<ServerSentEvent<Object>> start(ChatRequest request) {
        if (request.query() == null || request.query().isBlank()) {
            throw BizException.badRequest("提问不能为空");
        }
        Long userId = TenantContext.requireUserId();

        String conversationId = (request.conversationId() == null || request.conversationId().isBlank())
                ? UUID.randomUUID().toString()
                : request.conversationId().trim();

        List<Long> kbIds = request.scope();
        for (Long kbId : kbIds) {
            ragService.requireOwnedKb(kbId);
        }

        sessionService.ensure(conversationId, request.query(), kbIds);

        // 知识库改为「可选」：只有用户勾选了才挂 kbSearch，不选就是纯联网对话
        boolean hasKb = !kbIds.isEmpty();
        ToolBundle bundle = buildTools(kbIds, conversationId, hasKb);

        // 说明：这里不做服务端预检索。预检索会提前把片段塞进 prompt，
        // 模型因此不再调用 kbSearch，前端就收不到 ToolStart/ToolEnd，工具调用那一行会消失。
        // 「优先检索知识库」的策略改为在系统提示词里硬性约束（AgentConfig.composeInstructions），
        // 由模型自己发起检索，这样工具调用过程能正常展示。
        String query = withAttachment(request);

        String key = request.modelKey();
        ReactAgent agent = agentConfig.buildAssistant(modelProvider.chatModel(key), bundle.tools(),
                dataSource, taskManager, pauseStateStore, hasKb, agentConfig.persona(key));

        RunnableParams params = RunnableParams.builder()
                .conversationId(conversationId)
                .userId(String.valueOf(userId))
                .build();

        log.info("[chat] 新对话 cid={} userId={} 检索范围={} 工具数={} query长度={}",
                conversationId, userId, kbIds.isEmpty() ? "未选知识库" : kbIds,
                bundle.tools().size(), query.length());

        final String finalQuery = query;
        Flux<Object> source = agent.streamForResult(finalQuery, params)
                .cast(Object.class)
                .concatWith(citationTail(bundle, conversationId));

        return registry.registerStream(conversationId, String.valueOf(userId), source);
    }

    /**
     * 知识库限定入口（对齐 aicube-robot 的 `/knowledge-base/{id}/chat/stream`）：
     * 强制把检索范围收窄到这一个库，其余逻辑与 {@link #start} 完全一致。
     */
    public Flux<ServerSentEvent<Object>> startInKnowledgeBase(Long knowledgeBaseId, ChatRequest request) {
        ragService.requireOwnedKb(knowledgeBaseId);
        ChatRequest scoped = new ChatRequest(
                request.query(), request.conversationId(),
                List.of(knowledgeBaseId), request.recovery(), request.startSeq(), request.model(),
                request.attachmentName(), request.attachmentText());
        return start(scoped);
    }

    /** 从中断快照恢复（用户点了停止、或暂停后继续）。 */
    public Flux<ServerSentEvent<Object>> resume(ChatRequest request) {
        Long userId = TenantContext.requireUserId();
        String conversationId = request.conversationId();
        if (conversationId == null || conversationId.isBlank()) {
            throw BizException.badRequest("恢复请求缺少 conversationId");
        }
        if (!pauseStateStore.exists(conversationId)) {
            throw BizException.badRequest("该会话没有可恢复的中断状态");
        }
        // 恢复这一轮要沿用当初选的知识库范围：以前传 List.of()，恢复后就变成「检索全部库」了
        List<Long> kbIds = parseIds(sessionService.requireOwned(conversationId).getKnowledgeBaseIds());
        boolean hasKb = !kbIds.isEmpty();
        ToolBundle bundle = buildTools(kbIds, conversationId, hasKb);
        String key = request.modelKey();
        ReactAgent agent = agentConfig.buildAssistant(modelProvider.chatModel(key), bundle.tools(),
                dataSource, taskManager, pauseStateStore, hasKb, agentConfig.persona(key));

        log.info("[chat] 恢复中断会话 cid={} 检索范围={}", conversationId, kbIds.isEmpty() ? "未选知识库" : kbIds);
        Flux<Object> source = agent.resumeStream(conversationId, request.query())
                .cast(Object.class)
                .concatWith(citationTail(bundle, conversationId));

        return registry.registerStream(conversationId, String.valueOf(userId), source);
    }

    /** 断点续传：接回进行中的流。 */
    public Flux<ServerSentEvent<Object>> attach(String conversationId, Long startSeq) {
        if (!registry.isActive(conversationId)) {
            throw BizException.notFound("当前会话没有进行中的回答");
        }
        String owner = registry.ownerOf(conversationId);
        if (owner != null && !owner.equals(String.valueOf(TenantContext.requireUserId()))) {
            throw BizException.forbidden("无权访问该会话的流");
        }
        return registry.attach(conversationId, startSeq);
    }

    public boolean isActive(String conversationId) {
        return registry.isActive(conversationId);
    }

    public boolean hasInterruptedState(String conversationId) {
        return pauseStateStore.exists(conversationId);
    }

    /** 中断：走框架 interrupt，保留暂停快照，所以之后还能 resume。 */
    public boolean stop(String conversationId) {
        boolean interrupted = taskManager.interrupt(conversationId, "用户主动中断");
        log.info("[chat] 中断会话 cid={} interrupted={}", conversationId, interrupted);
        return interrupted;
    }

    // ==================== 内部 ====================

    /** 引用编号要等流跑完才收全，所以用 defer 延迟取值，接在流末尾。 */
    private Flux<Object> citationTail(ToolBundle bundle, String conversationId) {
        return Flux.defer(() -> {
            if (bundle.searchTool() == null) {
                return Flux.empty();
            }
            List<Citation> citations = bundle.searchTool().citations();
            if (citations.isEmpty()) {
                return Flux.empty();
            }
            log.info("[chat] 下发引用 {} 条", citations.size());
            // 落库：按轮次保存，刷新后回放还能看到每一轮的「来源」
            int turn = citationStore.nextTurn(conversationId);
            citationStore.saveTurn(conversationId, turn, citations);
            return Flux.<Object>just(new CitationEvent(citations));
        });
    }

    /**
     * 把「对话附件」拼进本次提问：附件只对这一轮生效，不写知识库；
     * 会话历史里存的仍是用户原始问题（sessionService.ensure 用的是 request.query()）。
     */
    private String withAttachment(ChatRequest request) {
        String text = request.attachmentText();
        if (text == null || text.isBlank()) {
            return request.query();
        }
        String name = request.attachmentName() == null || request.attachmentName().isBlank()
                ? "附件" : request.attachmentName();
        return request.query()
                + "\n\n【用户本轮上传的文件：" + name + "，内容如下】\n"
                + text.trim()
                + "\n【文件内容结束】\n"
                + "请优先依据上面的文件内容回答；文件里没有的信息再说明来源。";
    }

    private ToolBundle buildTools(List<Long> knowledgeBaseIds, String conversationId, boolean hasKb) {
        List<ToolCallback> tools = new ArrayList<>();
        KbSearchTool searchTool = null;

        // 1) 知识库检索（带引用编号）。历史闭包注入：工具入参只有 query，拿不到历史，
        //    而 pipeline 的多轮指代消解要用。
        if (hasKb) {
            List<Message> history = chatHistorySelector.recentQa(conversationId, 6);
            searchTool = ragService.newSearchTool(knowledgeBaseIds, history);
            tools.add(searchTool.callback());
        }

        // 2) 联网搜索：知识库搜不到时的兜底（服务端内置搜索，不额外收费）
        ZwProperties.Chat chatCfg = props.getModel().getChat();
        boolean searchable = chatCfg.getBaseUrl() != null
                && (chatCfg.getBaseUrl().contains("qianwenaiapi")
                    || chatCfg.getBaseUrl().contains("dashscope")
                    || chatCfg.getBaseUrl().contains("xiaomimimo"));
        if (searchable && chatCfg.getApiKey() != null && !chatCfg.getApiKey().isBlank()) {
            String searchModel = chatCfg.getModel();
            tools.add(new com.ziwei.zw.rag.tool.WebSearchTool(
                    chatCfg.getBaseUrl(), chatCfg.getApiKey(), searchModel).callback());
            log.info("[chat] 已挂载联网搜索工具（model={}）", searchModel);
        }

        // 3) 技能：已启用的全部自动挂载，不需要用户挑
        List<String> skillDirs = allEnabledSkillDirs();
        if (!skillDirs.isEmpty()) {
            try {
                SkillsTool.Builder builder = SkillsTool.builder();
                for (String dir : skillDirs) {
                    builder.addSkillsDirectory(dir);
                }
                tools.add(builder.build());
                log.info("[chat] 自动挂载已启用技能 {} 个", skillDirs.size());
            } catch (Exception e) {
                log.warn("[chat] 技能挂载失败（不影响主流程）: {}", e.getMessage());
            }
        }

        // 3) 任务追踪
        if (props.getAssistant().isEnableTodo()) {
            Collections.addAll(tools, TodoWriteTool.create());
        }

        // 4) 本地工具组：默认关闭（无沙箱 = 裸跑宿主机）
        if (props.getAgent().isAllowLocalTools()) {
            Collections.addAll(tools, FileSystemTools.create());
            Collections.addAll(tools, BashTool.create());
            Collections.addAll(tools, GrepTool.create());
            log.warn("[chat] 本地工具组已开启（无沙箱隔离），请确认这是你要的");
        }

        return new ToolBundle(tools, searchTool);
    }

    /** 会话里存的知识库范围（"1,3" 这种），解析回 id 列表。 */
    private static List<Long> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(t));
            } catch (NumberFormatException ignored) {
                // 脏数据直接跳过，不要因为一个 id 让整轮恢复失败
            }
        }
        return ids;
    }

    /** 所有已启用技能的目录。 */
    private List<String> allEnabledSkillDirs() {
        List<String> dirs = new ArrayList<>();
        for (String name : skillManager.enabledSkillNames()) {
            String dir = skillManager.resolveSkillDir(name);
            if (dir != null) {
                dirs.add(dir);
            }
        }
        return dirs;
    }
}
