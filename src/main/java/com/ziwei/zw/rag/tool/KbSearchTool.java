package com.ziwei.zw.rag.tool;

import com.agentx.ai.rag.common.MetadataKeys;
import com.agentx.ai.rag.pipeline.RagPipeline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * 知识库检索工具（引用溯源的核心）。
 *
 * <p>为什么不用框架自带的 {@code RagRetrievalTool}：它返回的是 {@code joinTexts()} 纯文本拼接，
 * **不带来源**，做不了引用。这里做了三件事：
 * <ol>
 *   <li>返回给模型的文本按 {@code [片段N] 来源：《文件名》· 章节：xxx} 排版，模型能直接照抄编号；</li>
 *   <li>同时把「编号 → 来源元数据 + 原文」收进 {@link Citation} 列表，流结束后发给前端渲染来源面板；</li>
 *   <li>Agentic 一次回答会检索**多轮**，编号按 {@code documentId + chunkId} **跨轮去重归并**，
 *       同一片段重复命中只复用原编号，不会出现 [3] 和 [7] 是同一段的情况。</li>
 * </ol>
 *
 * <p>另一个坑：工具入参只有 query，拿不到对话历史；而 pipeline 的指代消解需要 history。
 * 所以 {@link #chatHistory} 由构造时闭包注入。
 *
 * <p>线程安全：一次回答 new 一个实例（闭包持有本次的引用表），不做跨请求共享。
 */
@Slf4j
public final class KbSearchTool {

    public static final String TOOL_NAME = "kbSearch";

    private static final String DESCRIPTION = """
            检索用户已选择的知识库，返回与搜索语句相关的文档片段。
            注意：
            1. query 必须是独立完整的搜索语句，自己消解掉「它」「这个」之类的指代，不要把代词传进来；
            2. 只要本轮启用了知识库，任何问题都**先**调用本工具检索，不要跳过、不要直接联网；
               结果为空或明显无关时，换关键词再检索一次；
            3. 返回结果里 [片段N] 的 N 就是引用编号，回答时用 [N] 标注出处。
            """;

    private final RagPipeline pipeline;
    private final List<Message> chatHistory;

    /** key = documentId + '\u0000' + chunkId，保证同一片段只占一个编号 */
    private final LinkedHashMap<String, Citation> citations = new LinkedHashMap<>();

    public KbSearchTool(RagPipeline pipeline, List<Message> chatHistory) {
        this.pipeline = pipeline;
        this.chatHistory = chatHistory == null ? List.of() : chatHistory;
    }

    /** 交给 ReactAgent 注册的工具。 */
    public ToolCallback callback() {
        return FunctionToolCallback.builder(TOOL_NAME, this::search)
                .description(DESCRIPTION)
                .inputType(RagQuery.class)
                .build();
    }

    /** 本次回答累计命中的来源（按首次出现顺序）。 */
    public List<Citation> citations() {
        return List.copyOf(citations.values());
    }

    // ==================== 内部 ====================

    /**
     * 供服务端预检索调用：直接拿一句 query 去检索并返回带编号的片段文本。
     * 与工具入口 {@link #search} 共用同一套格式化逻辑，引用编号也会登记，
     * 所以模型回答时引用的 [N] 和最终下发的 Citations 是一致的。
     */
    public String prefetch(String query) {
        RagQuery q = new RagQuery(query);
        return search(q);
    }

    private synchronized String search(RagQuery request) {
        String query = request == null ? null : request.query();
        if (query == null || query.isBlank()) {
            return "检索语句为空，请提供完整的搜索语句";
        }
        List<Document> documents;
        try {
            documents = pipeline.retrieve(query, chatHistory);
        } catch (Exception e) {
            log.warn("[kbSearch] 检索失败 query={}: {}", query, e.getMessage());
            return "检索失败：" + e.getMessage();
        }
        if (documents == null || documents.isEmpty()) {
            return "未检索到相关内容";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("检索到 ").append(documents.size()).append(" 个片段：\n\n");
        for (Document doc : documents) {
            Citation citation = register(doc);
            sb.append("[片段").append(citation.no()).append("] 来源：《")
                    .append(citation.fileName()).append('》');
            if (!citation.headingPath().isBlank()) {
                sb.append(" · 章节：").append(citation.headingPath());
            }
            if (citation.assetUrl() != null || citation.contentType() != null
                    && !"text".equalsIgnoreCase(citation.contentType())) {
                // 图片块：原文往往只有占位符，模型需要知道这是图并给出可渲染地址
                sb.append(" · 类型：图片（地址 ").append(citation.assetUrl()).append('）');
            }
            sb.append('\n').append(citation.text()).append("\n\n");
        }
        return sb.toString().trim();
    }

    /** 命中片段登记：已出现过就复用编号，否则按顺序发新号。 */
    private Citation register(Document doc) {
        String documentId = str(doc, MetadataKeys.DOCUMENT_ID);
        String chunkId = str(doc, MetadataKeys.CHUNK_ID);
        String key = documentId + '\u0000' + chunkId;
        Citation exists = citations.get(key);
        if (exists != null) {
            return exists;
        }
        String headingPath = str(doc, MetadataKeys.HEADING_PATH);
        if (headingPath.isBlank()) {
            headingPath = str(doc, MetadataKeys.HEADING);
        }
        String fileName = str(doc, MetadataKeys.FILE_NAME);
        if (fileName.isBlank()) {
            fileName = "未命名文档";
        }
        // 图片块：MinerU 的 contentType 仍是 text（图片被转成描述文本），
        // 标识是 containsImg / imgUrls。把第一张图的可访问地址带进 Citation。
        Object typeObj = doc.getMetadata().get("contentType");
        String contentType = typeObj == null ? "text" : String.valueOf(typeObj);
        Object imgFlag = doc.getMetadata().get("containsImg");
        boolean containsImg = imgFlag != null && Boolean.parseBoolean(String.valueOf(imgFlag));
        String assetUrl = null;
        if (containsImg) {
            Object imgUrls = doc.getMetadata().get("imgUrls");
            if (imgUrls instanceof java.util.List<?> list && !list.isEmpty()) {
                String first = String.valueOf(list.get(0));
                // file:///C:/.../assets/{batchId}/images/x.jpg → /api/assets/{batchId}/images/x.jpg
                String rewritten = rewriteLocalAssetPaths(first);
                assetUrl = rewritten.startsWith("/api/assets/") ? rewritten : "/api/assets/" + fileNameOf(first);
            }
        }
        String text = doc.getText() == null ? "" : doc.getText();
        if (containsImg) {
            text = rewriteLocalAssetPaths(text);
        }
        Citation created = new Citation(
                citations.size() + 1,
                fileName,
                headingPath,
                chunkId,
                documentId,
                text,
                assetUrl,
                contentType);
        citations.put(key, created);
        return created;
    }

    /** 从文件路径里取出文件名（不带目录）。 */
    private static String fileNameOf(String path) {
        if (path == null) {
            return "";
        }
        String p = path.replace('\\', '/');
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    /**
     * MinerU 产出的图片地址是宿主机路径
     * {@code file:///C:/ziwei/data/assets/{batchId}/images/xxx.jpg}，
     * 浏览器打不开，统一改写成 {@code /api/assets/{batchId}/images/xxx.jpg}
     * （后端 AssetController 会按 assets 目录解析）。
     */
    private static String rewriteLocalAssetPaths(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        // file:///C:/.../assets/{batchId}/images/{name}  →  /api/assets/{batchId}/images/{name}
        String out = text.replaceAll(
                "file:///[A-Za-z]:[^)\"'\\s]*?/assets/([^)\"'\\s]+)",
                "/api/assets/$1");
        // 兜底：仍残留的 file:// 图片语法，按文件名反查
        out = out.replaceAll(
                "!\\[([^\\]]*)\\]\\(file:///[^)]*?/([^/)]+)\\)",
                "![$1](/api/assets/$2)");
        return out;
    }

    /**
     * 把片段里的图片引用改写成 {@code /api/assets/{assetId}}。
     * MinerU 解析出的图片块 text 常是 {@code ![](images/x.png)} 或纯占位符，
     * 这些相对路径在前端拿不到，必须换成资产接口地址。
     */
    private static String rewriteImageRefs(String text, Object assetId) {
        if (text == null || text.isBlank() || assetId == null) {
            return text;
        }
        String url = "/api/assets/" + assetId;
        String out = text.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "![](" + url + ")");
        if (!out.contains("![")) {
            // 原文根本没有 Markdown 图片语法，补一行让前端能显示
            out = (out.isBlank() ? "" : out + "\n\n") + "![](" + url + ")";
        }
        return out;
    }

    private String str(Document doc, String key) {
        Object value = doc.getMetadata().get(key);
        return value == null ? "" : value.toString();
    }
}
