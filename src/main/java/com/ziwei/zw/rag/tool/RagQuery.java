package com.ziwei.zw.rag.tool;

/**
 * kbSearch 工具入参。
 *
 * <p>只给一个 query，是要逼模型自己把指代消解掉（工具的 description 里也写了这条要求）——
 * Agentic 模式下一次回答可能检索多轮，每轮都是独立的搜索语句。
 */
public record RagQuery(String query) {
}
