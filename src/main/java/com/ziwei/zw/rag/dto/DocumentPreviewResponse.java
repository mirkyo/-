package com.ziwei.zw.rag.dto;

import java.util.List;

/**
 * 切块预览响应（不落库，确认后才入库）。
 */
public record DocumentPreviewResponse(
        String documentId,
        String fileName,
        int totalChunks,
        int parentChunks,
        int childChunks,
        List<ChunkPreview> chunks) {
}
