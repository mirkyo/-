package com.ziwei.zw.rag.dto;

/**
 * 单个切块预览。
 *
 * @param index    序号
 * @param role     chunk / parent / child
 * @param text     切块正文
 * @param type     块类型：text / image / table（来自解析器的 contentType）
 * @param assetUrl 图片资产的可访问地址（仅 image 块有值），前端用它渲染 {@code <img>}
 */
public record ChunkPreview(int index, String role, String text, String type, String assetUrl) {
}
