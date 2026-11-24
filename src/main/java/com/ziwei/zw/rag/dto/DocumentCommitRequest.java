package com.ziwei.zw.rag.dto;

/**
 * 确认入库请求。documentId 来自预览响应，用它去取暂存的切块，避免二次解析。
 */
public record DocumentCommitRequest(
        Long knowledgeBaseId,
        String documentId,
        String fileName,
        /** 原始文件字节数，仅用于展示 */
        Long fileSize,
        String parserType,
        String splitterType,
        Integer chunkSize,
        Integer overlap,
        Integer maxParentSize,
        Boolean parentChild) {
}
