package com.ziwei.zw.rag.tool;

/**
 * 一条引用来源。
 *
 * @param no          正文里 [n] 的编号
 * @param fileName    文档名
 * @param headingPath 章节路径（没有则空串）
 * @param chunkId     片段 ID
 * @param documentId  文档 ID（内容 hash）
 * @param text        片段原文
 * @param assetUrl    图片块的可访问地址（仅 content-type 为 image 时有值，其他为 null）
 * @param contentType 块类型：text / image / table
 */
public record Citation(int no, String fileName, String headingPath,
                       String chunkId, String documentId, String text,
                       String assetUrl, String contentType) {
}
