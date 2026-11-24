package com.ziwei.zw.rag.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 联网搜索工具：复用小米 MiMo 平台内置的 {@code web_search} 能力。
 *
 * <p>为什么这么做：MiMo 的 OpenAI 兼容接口支持 {@code "tools":[{"type":"web_search"}]}，
 * 由服务端自己联网检索并把结果喂回模型，**不需要第三方搜索 API、不额外收费**（只算 token）。
 * 但 Spring AI 的 tools 只支持 function calling（我们自己的方法），不支持这类 provider 内置工具，
 * 所以在工具内部再发一次带 web_search 的请求，把结论当工具结果交给主 Agent。
 */
@Slf4j
public final class WebSearchTool {

    public static final String TOOL_NAME = "webSearch";

    private static final String DESCRIPTION = """
            联网搜索（外部实时信息）。
            使用时机：
            1. 本轮启用了知识库时：必须先调用 kbSearch；只有 kbSearch 返回空或明确不相关，才允许调用本工具；
            2. 本轮没有启用知识库时：问题涉及外部或实时信息（新闻、天气、公开资料、行业动态等）才调用；
            3. query 用一句完整的话说明要搜索什么。
            返回：联网得到的事实要点，可能包含来源链接。
            """;

    private final String endpoint;
    private final String apiKey;
    private final String model;
    /** true 走顶层 enable_search（千问 MaaS），false 走 tools:web_search（MiMo） */
    private final boolean useEnableSearch;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public WebSearchTool(String baseUrl, String apiKey, String model) {
        String base = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        // base 已带 /v1（千问 MaaS）时不再重复拼接
        this.endpoint = base.endsWith("/v1")
                ? base + "/chat/completions"
                : base + "/v1/chat/completions";
        this.useEnableSearch = !base.contains("xiaomimimo");
        this.apiKey = apiKey;
        this.model = model;
    }

    public ToolCallback callback() {
        return FunctionToolCallback.builder(TOOL_NAME, this::search)
                .description(DESCRIPTION)
                .inputType(RagQuery.class)
                .build();
    }

    /** 工具主体：带 web_search 调一次 MiMo，返回它的结论 */
    public String search(RagQuery request) {
        String query = request == null || request.query() == null ? "" : request.query().trim();
        if (query.isEmpty()) {
            return "联网搜索失败：搜索内容为空";
        }
        if (apiKey == null || apiKey.isBlank()) {
            return "联网搜索不可用：未配置模型 api-key";
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "user", "content",
                    "请联网搜索并回答下面这个问题，要求：\n"
                            + "1. 给出简洁的事实要点（分条）；\n"
                            + "2. 尽量附上来源链接；\n"
                            + "3. 只用搜到的内容，不要编造。\n\n问题：" + query));
            body.put("messages", messages);
            // 两代接口的搜索参数不同：
            //  - 千问 MaaS / DashScope 兼容模式：顶层 enable_search
            //  - 小米 MiMo：tools:[{type:web_search}]（服务端内置工具）
            if (useEnableSearch) {
                body.put("enable_search", true);
                body.put("search_options", Map.of("forced_search", true));
            } else {
                body.put("tools", List.of(Map.of("type", "web_search")));
            }
            body.put("max_tokens", 900);

            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                log.warn("[webSearch] 调用失败 HTTP {}: {}", resp.statusCode(), head(resp.body()));
                return "联网搜索失败：HTTP " + resp.statusCode();
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            String text = content.isMissingNode() ? "" : content.asText("");
            if (text.isBlank()) {
                return "联网搜索没有返回内容";
            }
            log.info("[webSearch] query={} 返回 {} 字", query, text.length());
            return text;
        } catch (Exception e) {
            log.warn("[webSearch] 异常 query={}: {}", query, e.getMessage());
            return "联网搜索失败：" + e.getMessage();
        }
    }

    private String head(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
