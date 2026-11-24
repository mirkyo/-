package com.ziwei.zw.stream.dto;

import java.util.List;

/**
 * 对话请求。
 *
 * <p>发起：{@code query} 必填；{@code conversationId} 空 = 新会话；
 * {@code knowledgeBaseIds} 空 = 检索这个用户的全部知识库，否则只检索勾选的那几个。
 * 续传：{@code recovery=true} + {@code conversationId} + {@code startSeq}。
 */
public record ChatRequest(
        String query,
        String conversationId,
        /** 勾选的知识库；空 = 全部 */
        List<Long> knowledgeBaseIds,
        /** true 表示这是续传请求，只能走 /api/chat/recover */
        Boolean recovery,
        /** 从第几个事件开始续（包含）；不传按 1 处理 */
        Long startSeq,
        /** 模型档位：flash（Sonnet 5.5）/ pro（Opus 5.5）；空 = flash */
        String model,
        /** 对话临时附件：文件名（不落知识库，只给这一轮用） */
        String attachmentName,
        /** 对话临时附件：解析出的纯文本（前端上传 /api/chat/attach 得到） */
        String attachmentText) {

    /** 档位 key；空 = 第一个（默认 Opus 5.5） */
    public String modelKey() {
        return (model == null || model.isBlank()) ? "opus55" : model.trim();
    }

    public boolean isRecovery() {
        return Boolean.TRUE.equals(recovery);
    }

    /** 规范化的知识库范围：null / 空列表都归一成空列表。 */
    public List<Long> scope() {
        return knowledgeBaseIds == null ? List.of() : knowledgeBaseIds;
    }
}
