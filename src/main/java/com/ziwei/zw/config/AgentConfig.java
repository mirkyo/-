package com.ziwei.zw.config;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.agent.internal.AgentTaskManager;
import com.agentx.ai.core.interrupt.JdbcPauseStateStore;
import com.agentx.ai.core.memory.store.SessionMessageStore;
import com.agentx.ai.core.memory.util.ChatHistorySelector;
import com.agentx.ai.core.model.ThinkingMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;

/**
 * 助手运行时装配。
 *
 * <p>平台只有一个助手（配置在 {@code app.assistant.*}），**没有智能体广场**。
 * 技能不按智能体挑：**启用的技能全部自动挂载**；知识库也不「绑定」，
 * 检索默认覆盖当前用户自己的全部知识库（前端可按库收窄）。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AgentConfig {

    private final ZwProperties props;

    /** 会话消息存储（读 agentx_session，用于取历史做指代消解）。 */
    @Bean
    public SessionMessageStore sessionMessageStore(DataSource dataSource) {
        SessionMessageStore store = new SessionMessageStore(dataSource);
        store.initialize();
        return store;
    }

    @Bean
    public ChatHistorySelector chatHistorySelector(SessionMessageStore sessionMessageStore) {
        return new ChatHistorySelector(sessionMessageStore);
    }

    /** 中断快照存储（agentx_pause_state），停止后可恢复。 */
    @Bean
    public JdbcPauseStateStore pauseStateStore(DataSource dataSource) {
        JdbcPauseStateStore store = new JdbcPauseStateStore(dataSource);
        store.initialize();
        return store;
    }

    /** 共享任务管理器：{@code /api/chat/stop} 靠它按 conversationId 命中正在跑的会话。 */
    @Bean
    public AgentTaskManager agentTaskManager() {
        return new AgentTaskManager();
    }

    /**
     * 组装 ReactAgent。
     *
     * @param hasKnowledgeBase 当前用户有没有知识库 —— 决定要不要在提示词里加「必须标注引用」的硬要求
     */
    public ReactAgent buildAssistant(ChatModel chatModel, List<ToolCallback> tools,
                                     DataSource dataSource, AgentTaskManager taskManager,
                                     JdbcPauseStateStore pauseStateStore, boolean hasKnowledgeBase,
                                     String fakeName) {
        ZwProperties.Assistant cfg = props.getAssistant();

        ReactAgent.Builder builder = ReactAgent.builder()
                .name("assistant")
                .description(cfg.getName())
                .chatModel(chatModel)
                .tools(tools)
                .instructions(composeInstructions(cfg, hasKnowledgeBase, fakeName))
                .maxRounds(props.getAgent().getDefaultMaxRounds())
                .dataSource(dataSource)
                .enableSession(true)
                .enableTrace(props.getAgent().isEnableTrace())
                .taskManager(taskManager)
                .stateStore(pauseStateStore);

        if (cfg.isEnableThinking()) {
            builder.thinkingMode(ThinkingMode.REASONING_CONTENT);
        }
        if (cfg.isEnableAskUser()) {
            builder.askUser(true);
        }
        return builder.build();
    }

    /** 通用前言 + yml 里的补充提示词。有知识库时追加引用标注的硬要求。 */
    /** 档位的人设显示名（= 系统提示词里的自称）；key 命中不了就用第一个 */
    public String persona(String key) {
        var list = props.getAssistant().getModels();
        if (list == null || list.isEmpty()) {
            return "Opus 5.5";
        }
        for (var o : list) {
            if (o.getKey() != null && o.getKey().equalsIgnoreCase(key)) {
                return o.getLabel();
            }
        }
        return list.get(0).getLabel();
    }

    private String composeInstructions(ZwProperties.Assistant cfg, boolean hasKnowledgeBase,
                                       String fakeName) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是「知微」平台上的智能助手");
        if (cfg.getName() != null && !cfg.getName().isBlank()) {
            sb.append("，名字叫「").append(cfg.getName()).append("」");
        }
        sb.append("。\n\n");
        sb.append("""
                # 模型身份
                - 你的底层模型是 %s。
                - 当用户问「你是什么模型 / 用的什么大模型」时，直接回答：我是 %s。不要否认、不要说其他名字，也不要展开讨论版本细节。
                """.formatted(fakeName, fakeName));
        sb.append("""
                # 通用要求
                - 回答准确、专业、结论明确；不确定的地方要说明不确定，禁止编造。
                - 使用 Markdown 排版；涉及对比、参数时优先用表格。
                """);
        sb.append("""
                # 联网搜索
                - 回答里凡是用到联网内容，要明确说明「以下内容来自网络」，不要把网络信息说成公司内部资料。
                - 联网结果只用于本次回答，不要臆造没有搜到的内容。
                - 不要反问用户要补充信息（如城市、时间、版本等）：缺参数就按最合理的默认值继续，
                  或者直接联网搜索，把"如果你指的是 XX，可以告诉我"放在回答末尾。
                """);
        if (hasKnowledgeBase) {
            sb.append("""
                    # 知识库检索（本轮已启用，最高优先级）
                    - 用户本轮选择了知识库，**必须优先检索知识库**。
                    - 第一步永远先调用 kbSearch，不要跳过、不要直接联网、不要凭记忆作答；即使你觉得问题是常识也要先检索。
                    - 只依据检索到的片段回答，每条结论后用 [1] [2] 标注来源编号，编号对应结果里的 [片段N]。
                    - 检索结果为空或明显无关时：换关键词再检索一次；仍然没有，就明说「知识库里没有找到相关内容」。
                    - 只有在知识库确实没有相关内容、且问题涉及外部或实时信息时，才允许调用 webSearch，
                      并在回答里注明「以下内容来自网络」。
                    """);
        } else {
            sb.append("""
                    # 本次没有挂知识库
                    - 用户没有选择知识库，不要调用 kbSearch。
                    - 涉及外部 / 实时信息、或者超出你知识范围的问题，直接调 webSearch 联网搜索；
                      纯常识问题可以直接回答。
                    """);
        }
        if (cfg.getInstructions() != null && !cfg.getInstructions().isBlank()) {
            sb.append("\n# 补充要求\n").append(cfg.getInstructions().trim()).append('\n');
        }
        return sb.toString();
    }
}
