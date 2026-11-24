package com.ziwei.zw.stream.dto;

import com.ziwei.zw.rag.tool.Citation;

import java.util.List;

/**
 * 引用来源事件。
 *
 * <p>框架的 {@code AgentStreamEvent} 是 sealed，不能扩展；所以这里单独做一个类型，
 * 序列化出来带 {@code "type":"Citations"}，与其它事件在同一个 SSE 通道里下发。
 * 放在正文之后、Complete 之前。
 */
public final class CitationEvent {

    private final List<Citation> items;

    public CitationEvent(List<Citation> items) {
        this.items = items;
    }

    public String getType() {
        return "Citations";
    }

    public List<Citation> getItems() {
        return items;
    }
}
