package com.ziwei.zw.session;

import com.agentx.ai.core.memory.store.SessionMessageStore;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ziwei.zw.common.BizException;
import com.ziwei.zw.common.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话：kb_conversation 管「会话 ↔ 用户/知识库范围」与标题，
 * 消息明细直接读框架的 agentx_session（不重复存）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private static final String STATE_ORIGINAL = "original_messages";

    private final SessionMapper sessionMapper;
    private final SessionAdminMapper sessionAdminMapper;
    private final SessionMessageStore sessionMessageStore;

    /** 确保会话记录存在；标题用首轮提问截断生成。 */
    public void ensure(String conversationId, String query, List<Long> knowledgeBaseIds) {
        Conversation exists = sessionMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getConversationId, conversationId));
        if (exists != null) {
            // 校验归属：防止拿别人的 conversationId 续写他人会话并读到历史
            if (!TenantContext.requireUserId().equals(exists.getUserId())) {
                throw BizException.forbidden("无权访问该会话");
            }
            exists.setMessageCount((exists.getMessageCount() == null ? 0 : exists.getMessageCount()) + 1);
            sessionMapper.updateById(exists);
            return;
        }
        Conversation conversation = new Conversation();
        conversation.setTenantId(TenantContext.getTenantId());
        conversation.setUserId(TenantContext.requireUserId());
        conversation.setKnowledgeBaseIds(join(knowledgeBaseIds));
        conversation.setConversationId(conversationId);
        conversation.setTitle(titleOf(query));
        conversation.setMessageCount(1);
        sessionMapper.insert(conversation);
    }

    private String join(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        return ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    public Map<String, Object> list(int page, int size) {
        // 只返回当前用户自己的会话，避免越权看到他人会话标题
        Page<Conversation> p = sessionMapper.selectPage(new Page<>(page + 1L, size),
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getUserId, TenantContext.requireUserId())
                        .orderByDesc(Conversation::getUpdateTime));
        return Map.of(
                "conversations", p.getRecords(),
                "total", p.getTotal(),
                "page", page,
                "size", size);
    }

    public void delete(String conversationId) {
        Conversation conversation = requireOwned(conversationId);
        sessionMapper.deleteById(conversation.getId());
        // 框架三张表也一起清掉，否则重建同 cid 会读到旧消息
        sessionAdminMapper.deleteSession(conversationId);
        sessionAdminMapper.deleteConversation(conversationId);
        sessionAdminMapper.deletePauseState(conversationId);
        log.info("[session] 删除会话 cid={}", conversationId);
    }

    /**
     * 会话消息明细：读框架 agentx_session 的 original_messages，
     * 只保留用户与助手的真实对话（过滤工具调用等中间消息）。
     */
    public List<Map<String, String>> messages(String conversationId) {
        requireOwned(conversationId);
        List<Message> originals = sessionMessageStore.getMessages(conversationId, STATE_ORIGINAL);
        List<Map<String, String>> result = new ArrayList<>();
        for (Message message : originals) {
            String role = switch (message.getMessageType()) {
                case USER -> "user";
                case ASSISTANT -> "assistant";
                default -> null;
            };
            if (role == null) {
                continue;
            }
            String text = message.getText() == null ? "" : message.getText();
            // 中断 / 重新生成会在库里留一条空的 assistant 占位消息。
            // 前端按 messages 与 turns 的下标对齐渲染，这条空消息会让一轮回答被画成两个
            // 「星标 + 过程行」，正文跑到第二个里去 —— 直接丢掉。
            if ("assistant".equals(role) && text.isBlank()) {
                continue;
            }
            Map<String, String> item = new LinkedHashMap<>();
            item.put("role", role);
            item.put("content", text);
            result.add(item);
        }
        return result;
    }

    public Conversation requireOwned(String conversationId) {
        Conversation conversation = sessionMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getConversationId, conversationId));
        if (conversation == null) {
            throw BizException.notFound("会话不存在");
        }
        if (!TenantContext.requireUserId().equals(conversation.getUserId())) {
            throw BizException.forbidden("无权访问该会话");
        }
        return conversation;
    }

    private String titleOf(String query) {
        if (query == null || query.isBlank()) {
            return "新对话";
        }
        String trimmed = query.trim().replaceAll("\\s+", " ");
        return trimmed.length() > 30 ? trimmed.substring(0, 30) + "…" : trimmed;
    }
}
