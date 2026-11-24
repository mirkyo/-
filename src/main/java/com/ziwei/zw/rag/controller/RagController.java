package com.ziwei.zw.rag.controller;

import com.ziwei.zw.common.R;
import com.ziwei.zw.rag.domain.KnowledgeBase;
import com.ziwei.zw.rag.domain.KnowledgeDocument;
import com.ziwei.zw.rag.dto.DocumentCommitRequest;
import com.ziwei.zw.rag.dto.DocumentPreviewRequest;
import com.ziwei.zw.rag.dto.DocumentPreviewResponse;
import com.ziwei.zw.rag.service.RagService;
import com.ziwei.zw.stream.ChatStreamService;
import com.ziwei.zw.stream.dto.ChatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 知识库接口：建库 / 文档列表 / 上传预览 / 确认入库 / 删除。
 *
 * <p>问答不在这里 —— 对话统一走 {@code POST /api/chat/stream}，
 * 知识库是以 {@code kbSearch} 工具的形式进 Agent 的（检索范围由请求里的 knowledgeBaseIds 决定）。
 */
@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;
    private final ChatStreamService chatStreamService;

    public record KnowledgeBaseRequest(String name, String description) {
    }

    // ==================== 知识库 ====================

    @GetMapping("/knowledge-base")
    public R<List<KnowledgeBase>> list() {
        return R.ok(ragService.list());
    }

    @PostMapping("/knowledge-base")
    public R<Long> create(@RequestBody KnowledgeBaseRequest req) {
        return R.ok(ragService.create(req.name(), req.description()));
    }

    @PutMapping("/knowledge-base/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody KnowledgeBaseRequest req) {
        ragService.update(id, req.name(), req.description());
        return R.ok();
    }

    @DeleteMapping("/knowledge-base/{id}")
    public R<Void> delete(@PathVariable Long id) {
        ragService.delete(id);
        return R.ok();
    }

    // ==================== 文档 ====================

    @GetMapping("/knowledge-base/{id}/document")
    public R<List<KnowledgeDocument>> documents(@PathVariable("id") Long knowledgeBaseId) {
        return R.ok(ragService.listDocuments(knowledgeBaseId));
    }

    @PostMapping(value = "/document/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<DocumentPreviewResponse> preview(@jakarta.validation.Valid @ModelAttribute DocumentPreviewRequest request) {
        return R.ok(ragService.preview(request));
    }

    @PostMapping("/document/commit")
    public R<Void> commit(@RequestBody DocumentCommitRequest request) {
        ragService.commit(request);
        return R.ok();
    }

    @DeleteMapping("/document/{id}")
    public R<Void> deleteDocument(@PathVariable Long id) {
        ragService.deleteDocument(id);
        return R.ok();
    }

    // ==================== 问答（限定到单个知识库） ====================

    /**
     * 知识问答（Agentic RAG 流式），检索范围强制收窄到这个知识库。
     * 与 {@code /api/chat/stream} 等价，只是不必在 body 里传 knowledgeBaseIds。
     */
    @PostMapping(value = "/knowledge-base/{id}/chat/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> chatStream(@PathVariable("id") Long knowledgeBaseId,
                                                    @RequestBody ChatRequest request) {
        return chatStreamService.startInKnowledgeBase(knowledgeBaseId, request);
    }
}
