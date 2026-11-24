package com.ziwei.zw.stream;

import com.agentx.ai.rag.reader.RawDocument;
import com.agentx.ai.rag.parser.tika.TikaDocumentParser;
import com.ziwei.zw.common.BizException;
import com.ziwei.zw.common.R;
import com.ziwei.zw.stream.dto.ChatRequest;
import com.ziwei.zw.config.ZwProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 对话接口：发起 / 断点续传 / 状态探测 / 停止 / 从中断恢复。
 *
 * <p>SSE 事件里既有框架的 {@code AgentStreamEvent}（Thinking/Text/ToolStart/ToolEnd/...），
 * 也有我们自己的 {@code CitationEvent}，所以泛型是 {@code Object}，前端按 {@code type} 分发。
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    /** 允许的附件扩展名：能抽成纯文字的类型 */
    private static final java.util.Set<String> ALLOWED_EXT = java.util.Set.of(
            "txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm", "log",
            "yml", "yaml", "properties", "ini", "conf", "sql",
            "java", "js", "ts", "py", "go", "rs", "c", "cpp", "h", "cs", "php", "rb", "sh",
            "pdf", "docx", "doc", "pptx", "ppt", "xlsx", "xls", "rtf",
            // 图片：不是"抽文字"，而是交给 MiMo 视觉生成描述
            "jpg", "jpeg", "png", "webp", "gif", "bmp");

    private static final java.util.Set<String> IMAGE_EXT = java.util.Set.of(
            "jpg", "jpeg", "png", "webp", "gif", "bmp");

    private final ChatStreamService chatStreamService;
    private final ZwProperties props;
    private final com.ziwei.zw.session.SessionService sessionService;

    /** 发一轮（新会话或同一会话追问）。body: {query, conversationId?, knowledgeBaseId?} */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> stream(@RequestBody ChatRequest request) {
        return chatStreamService.start(request);
    }

    /**
     * 对话附件：把文件当场解析成纯文本，随这一轮提问一起发给模型（不写知识库）。
     * 前端拿到 text 后，在 /stream 的请求体里带上 attachmentName / attachmentText。
     */
    @PostMapping("/attach")
    public R<Map<String, String>> attach(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("请选择要上传的文件");
        }
        if (file.getSize() > 5L * 1024 * 1024) {
            throw BizException.badRequest("文件太大（上限 5MB）");
        }
        String name = file.getOriginalFilename() == null ? "附件" : file.getOriginalFilename();
        // 只收「能解析成文字」的格式：文本/代码 + 常见文档
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            ext = name.substring(dot + 1).toLowerCase();
        }
        if (!ALLOWED_EXT.contains(ext)) {
            throw BizException.badRequest("不支持的文件类型 ." + ext
                    + "，请上传文本/代码或 pdf、docx、pptx、xlsx、csv 等可解析文件");
        }
        String text;
        try {
            byte[] bytes = file.getBytes();
            // 图片：MiMo 自带视觉能力，让它生成一段描述当作文本上下文
            if (IMAGE_EXT.contains(ext)) {
                String desc = describeImage(name, bytes);
                if (desc == null || desc.isBlank()) {
                    throw BizException.badRequest("图片识别没有返回内容，请换一张试试");
                }
                return R.ok(Map.of("fileName", name, "text", desc, "chars", String.valueOf(desc.length()),
                        "kind", "image"));
            }
            List<org.springframework.ai.document.Document> docs =
                    new TikaDocumentParser().parseToDocuments(
                            new RawDocument(name, bytes, Map.of()));
            text = docs.stream()
                    .map(org.springframework.ai.document.Document::getText)
                    .filter(t -> t != null && !t.isBlank())
                    .collect(Collectors.joining("\n\n"));
        } catch (Exception e) {
            throw BizException.badRequest("文件解析失败：" + e.getMessage());
        }
        if (text.isBlank()) {
            throw BizException.badRequest("没有解析出文字内容（可能是图片或扫描件）");
        }
        // Tika 会把文档内嵌图片留成 "image1.png" 这种占位行，清掉避免干扰模型
        text = text.replaceAll("(?m)^\\s*image\\d+\\.(png|jpe?g|webp|gif|bmp)\\s*$", "")
                   .replaceAll("\\n{3,}", "\n\n")
                   .trim();
        if (text.isEmpty()) {
            throw BizException.badRequest("没有解析出文字内容");
        }
        int chars = text.length();
        String cut = chars > 12000 ? text.substring(0, 12000) + "\n…（内容过长已截断）" : text;
        return R.ok(Map.of("fileName", name, "text", cut, "chars", String.valueOf(chars)));
    }

    /**
     * 图片 → 文字：调 MiMo（app.model.multimodal）让视觉模型描述图片。
     *
     * <p>注意 max_tokens 要给足：MiMo 会先输出 reasoning_content，
     * 给太小会出现「content 为空、token 全被思考吃掉」的假失败。
     */
    private String describeImage(String fileName, byte[] bytes) {
        var mm = props.getModel().getMultimodal();
        if (mm.getApiKey() == null || mm.getApiKey().isBlank()) {
            throw BizException.badRequest("未配置视觉模型，无法识别图片");
        }
        String lower = fileName.toLowerCase();
        String mime = lower.endsWith(".png") ? "image/png"
                : lower.endsWith(".webp") ? "image/webp"
                : lower.endsWith(".gif") ? "image/gif"
                : lower.endsWith(".bmp") ? "image/bmp" : "image/jpeg";
        String dataUrl = "data:" + mime + ";base64,"
                + java.util.Base64.getEncoder().encodeToString(bytes);
        String base = mm.getBaseUrl() == null ? "" : mm.getBaseUrl().replaceAll("/+$", "");
        String endpoint = base.endsWith("/v1") ? base + "/chat/completions" : base + "/v1/chat/completions";
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("model", mm.getModel());
            body.put("max_tokens", 1200);
            body.put("messages", List.of(Map.of("role", "user", "content", List.of(
                    Map.of("type", "text", "text",
                            "请详细描述这张图片：主体、颜色、文字、布局、动作与场景，300 字以内。只描述看到的内容。"),
                    Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))))));
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(endpoint))
                    .timeout(java.time.Duration.ofSeconds(90))
                    .header("Authorization", "Bearer " + mm.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), java.nio.charset.StandardCharsets.UTF_8))
                    .build();
            var resp = java.net.http.HttpClient.newHttpClient()
                    .send(req, java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                throw BizException.badRequest("图片识别失败：HTTP " + resp.statusCode());
            }
            var node = mapper.readTree(resp.body()).path("choices").path(0).path("message");
            String content = node.path("content").asText("");
            if (content.isBlank()) {
                content = node.path("reasoning_content").asText("");   // 兜底
            }
            return content;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.badRequest("图片识别失败：" + e.getMessage());
        }
    }

    /** 断点续传。body: {conversationId, recovery:true, startSeq} */
    @PostMapping(value = "/recover", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> recover(@RequestBody ChatRequest request) {
        if (request.conversationId() == null || request.conversationId().isBlank()) {
            throw new IllegalArgumentException("恢复请求缺少 conversationId");
        }
        sessionService.requireOwned(request.conversationId());
        return chatStreamService.attach(request.conversationId(), request.startSeq());
    }

    /** 探针：该会话有没有进行中的流（刷新 / 点历史会话时前端先问一下）。 */
    @GetMapping("/status")
    public R<Map<String, Boolean>> status(@RequestParam String conversationId) {
        sessionService.requireOwned(conversationId);
        return R.ok(Map.of(
                "active", chatStreamService.isActive(conversationId),
                "interrupted", chatStreamService.hasInterruptedState(conversationId)));
    }

    /** 停止：走框架 interrupt，落暂停快照，之后还能恢复。用 POST —— GET 不该有副作用。 */
    /** 可选模型档位（前端下拉框用，label 即界面显示名） */
    @GetMapping("/models")
    public R<List<Map<String, String>>> models() {
        List<Map<String, String>> out = props.getAssistant().getModels().stream()
                .map(o -> Map.of("key", o.getKey(), "label", o.getLabel()))
                .toList();
        return R.ok(out);
    }

    @PostMapping("/stop")
    public R<Boolean> stop(@RequestParam String conversationId) {
        sessionService.requireOwned(conversationId);
        return R.ok(chatStreamService.stop(conversationId));
    }

    /** 从中断快照恢复。body: {conversationId, query?} */
    @PostMapping(value = "/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> resume(@RequestBody ChatRequest request) {
        if (request.conversationId() != null && !request.conversationId().isBlank()) {
            sessionService.requireOwned(request.conversationId());
        }
        return chatStreamService.resume(request);
    }
}
