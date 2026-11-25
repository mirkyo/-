package com.ziwei.zw.rag.service;

import com.agentx.ai.rag.asset.LocalDocumentAssetStore;
import com.agentx.ai.rag.common.ChunkRole;
import com.agentx.ai.rag.common.DocumentIdGenerator;
import com.agentx.ai.rag.common.MetadataKeys;
import com.agentx.ai.rag.parser.mineru.MineruDocumentParser;
import com.agentx.ai.rag.parser.tika.TikaDocumentParser;
import com.agentx.ai.rag.pipeline.DefaultRagPipeline;
import com.agentx.ai.rag.pipeline.RagPipeline;
import com.agentx.ai.rag.query.QueryExpanders;
import com.agentx.ai.rag.query.QueryTransformers;
import com.agentx.ai.rag.reader.RawDocument;
import com.agentx.ai.rag.retrieve.EnhancedRetriever;
import com.agentx.ai.rag.retrieve.reranker.HttpReranker;
import com.agentx.ai.rag.splitter.HeadingSplitter;
import com.agentx.ai.rag.splitter.RecursiveSplitter;
import com.agentx.ai.rag.splitter.SlidingWindowSplitter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ziwei.zw.common.BizException;
import com.ziwei.zw.common.TenantContext;
import com.ziwei.zw.config.ModelProvider;
import com.ziwei.zw.config.ZwProperties;
import com.ziwei.zw.rag.config.RagStores;
import com.ziwei.zw.rag.domain.KnowledgeBase;
import com.ziwei.zw.rag.domain.KnowledgeDocument;
import com.ziwei.zw.rag.dto.ChunkPreview;
import com.ziwei.zw.rag.dto.DocumentCommitRequest;
import com.ziwei.zw.rag.dto.DocumentPreviewRequest;
import com.ziwei.zw.rag.dto.DocumentPreviewResponse;
import com.ziwei.zw.rag.mapper.KnowledgeBaseMapper;
import com.ziwei.zw.rag.mapper.KnowledgeDocumentMapper;
import com.ziwei.zw.rag.store.ChunkPreviewStore;
import com.ziwei.zw.rag.tool.KbSearchTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 知识库服务：库里表 + 两阶段入库（预览 → 确认）+ 检索编排。
 *
 * <p>骨架照抄 aicube-robot 的 {@code RagServiceImpl}，差别：
 * <ul>
 *   <li>隔离条件多一维 {@code tenantId}（aicube-robot 只有 userId / knowledgeBaseId）；</li>
 *   <li>父块库用 PG 的 {@code JdbcDocumentStore}，不是 Redis；</li>
 *   <li>切块暂存用 Caffeine，不是 Redis；</li>
 *   <li>检索工具换成带引用编号的 {@link KbSearchTool}。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    /** MinerU 云端不接受的扩展名（框架本地白名单放行，但云端会报 -60002） */
    private static final Set<String> MINERU_REJECTED_EXT = Set.of(".md", ".markdown");

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final ChunkPreviewStore previewStore;
    private final RagStores ragStores;
    private final ZwProperties props;
    private final ModelProvider modelProvider;

    // ==================== 知识库 ====================

    public Long create(String name, String description) {
        if (name == null || name.isBlank()) {
            throw BizException.badRequest("知识库名称不能为空");
        }
        Long userId = TenantContext.requireUserId();
        Long exists = knowledgeBaseMapper.selectCount(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getUserId, userId)
                .eq(KnowledgeBase::getName, name));
        if (exists != null && exists > 0) {
            throw BizException.badRequest("知识库名称已存在：" + name);
        }
        KnowledgeBase kb = new KnowledgeBase();
        kb.setTenantId(TenantContext.getTenantId());
        kb.setUserId(userId);
        kb.setName(name);
        kb.setDescription(description == null ? "" : description);
        knowledgeBaseMapper.insert(kb);
        log.info("[rag] 建库 id={} name={} userId={}", kb.getId(), name, userId);
        return kb.getId();
    }

    public List<KnowledgeBase> list() {
        return knowledgeBaseMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getUserId, TenantContext.requireUserId())
                .orderByDesc(KnowledgeBase::getId));
    }

    public void update(Long id, String name, String description) {
        KnowledgeBase kb = requireOwnedKb(id);
        if (name != null && !name.isBlank()) {
            // 改名也要查重（对齐 aicube-robot：排除自己，否则会撞 uk_tenant_user_name 报 SQL 错）
            Long exists = knowledgeBaseMapper.selectCount(new LambdaQueryWrapper<KnowledgeBase>()
                    .eq(KnowledgeBase::getUserId, kb.getUserId())
                    .eq(KnowledgeBase::getName, name)
                    .ne(KnowledgeBase::getId, id));
            if (exists != null && exists > 0) {
                throw BizException.badRequest("知识库名称已存在：" + name);
            }
            kb.setName(name);
        }
        if (description != null) {
            kb.setDescription(description);
        }
        knowledgeBaseMapper.updateById(kb);
    }

    /** 删库：连带清掉所有文档的向量与父块（物理删，避免唯一键占位）。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireOwnedKb(id);
        List<KnowledgeDocument> docs = documentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getKnowledgeBaseId, id));
        for (KnowledgeDocument doc : docs) {
            purgeVector(doc.getDocumentId());
        }
        documentMapper.deletePhysicallyByKbId(id);
        knowledgeBaseMapper.deletePhysically(id);
        log.info("[rag] 删库 id={} 文档数={}", id, docs.size());
    }

    // ==================== 文档 ====================

    public List<KnowledgeDocument> listDocuments(Long knowledgeBaseId) {
        requireOwnedKb(knowledgeBaseId);
        return documentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                .orderByDesc(KnowledgeDocument::getId));
    }

    /**
     * 第一步：解析 + 切块，**不落库**，只暂存起来给前端预览。
     *
     * <p>{@code documentId = 内容 SHA-256}，所以同一份内容重复上传天然幂等。
     */
    public DocumentPreviewResponse preview(DocumentPreviewRequest request) {
        MultipartFile file = request.getFile();
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("请选择要上传的文件");
        }
        String fileName = file.getOriginalFilename() == null ? "未命名" : file.getOriginalFilename();
        checkFileSize(file);

        byte[] content;
        try {
            content = file.getBytes();
        } catch (Exception e) {
            throw BizException.badRequest("读取文件失败：" + e.getMessage());
        }
        String documentId = DocumentIdGenerator.getDocId(content);

        String parser = request.parserOrDefault();
        String splitter = request.splitterOrDefault();
        int chunkSize = request.getChunkSize() == null ? props.getRag().getChunk().getChunkSize() : request.getChunkSize();
        int overlap = request.getOverlap() == null ? props.getRag().getChunk().getOverlap() : request.getOverlap();
        int maxParentSize = request.getMaxParentSize() == null
                ? props.getRag().getChunk().getMaxParentSize() : request.getMaxParentSize();
        int headingLevel = request.getHeadingLevel() == null
                ? props.getRag().getChunk().getHeadingLevel() : request.getHeadingLevel();
        boolean parentChild = Boolean.TRUE.equals(request.getParentChild());

        log.info("[rag] 预览 cut fileName={} parser={} splitter={} chunkSize={} overlap={} parentChild={}",
                fileName, parser, splitter, chunkSize, overlap, parentChild);

        List<Document> chunks = parseAndSplit(content, fileName, parser, splitter,
                chunkSize, overlap, maxParentSize, headingLevel, parentChild,
                Boolean.TRUE.equals(request.getEnableImageUnderstanding()));

        int maxChunks = props.getRag().getChunk().getMaxChunks();
        if (chunks.size() > maxChunks) {
            throw BizException.badRequest("切块数量过多（" + chunks.size() + " 块），最大 " + maxChunks
                    + "，请增大 chunkSize 或拆分文档");
        }

        previewStore.save(documentId, chunks);
        log.info("[rag] 预览完成 documentId={} totalChunks={}", documentId, chunks.size());

        List<ChunkPreview> previews = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Document doc = chunks.get(i);
            Map<String, Object> meta = doc.getMetadata();
            // 块类型 + 图片资产：MinerU 解析时 IMAGE 块的 metadata 带着 assetId（{documentId}/{文件名}）
            Object typeObj = meta.get("contentType");
            String type = typeObj == null ? "text" : String.valueOf(typeObj);
            Object assetId = meta.get("assetId");
            String assetUrl = (assetId == null || String.valueOf(assetId).isBlank())
                    ? null
                    : "/api/assets/" + assetId;
            previews.add(new ChunkPreview(i, roleOf(doc), doc.getText(), type, assetUrl));
        }
        return new DocumentPreviewResponse(
                documentId,
                fileName,
                chunks.size(),
                countByRole(chunks, ChunkRole.PARENT),
                countByRole(chunks, ChunkRole.CHILD),
                previews);
    }

    /**
     * 第二步：确认入库。从暂存里取回切块（不重新解析），先删后插写进向量库与父块库。
     */
    @Transactional(rollbackFor = Exception.class)
    public void commit(DocumentCommitRequest request) {
        KnowledgeBase kb = requireOwnedKb(request.knowledgeBaseId());
        String documentId = request.documentId();
        if (documentId == null || documentId.isBlank()) {
            throw BizException.badRequest("documentId 不能为空");
        }
        List<Document> chunks = previewStore.get(documentId);
        if (chunks == null) {
            throw BizException.badRequest("切块预览已过期，请重新上传预览");
        }

        // 同内容重复入库：先把旧记录物理删掉，避免 uk_kb_doc 冲突
        documentMapper.deletePhysically(kb.getId(), documentId);

        Map<String, Object> extra = new HashMap<>();
        extra.put("userId", String.valueOf(TenantContext.requireUserId()));
        extra.put("knowledgeBaseId", String.valueOf(kb.getId()));
        // index 内部就是「先按 documentId 删、再写」，天然幂等
        ragStores.documentStore().index(documentId, chunks, extra);

        KnowledgeDocument record = new KnowledgeDocument();
        record.setTenantId(TenantContext.getTenantId());
        record.setKnowledgeBaseId(kb.getId());
        record.setFileName(request.fileName() == null ? "未命名" : request.fileName());
        // 扩展名从文件名推；大小前端带过来（纯展示信息）
        String ext = extensionOf(request.fileName());
        record.setFileType(ext.startsWith(".") ? ext.substring(1).toUpperCase() : "");
        record.setFileSize(request.fileSize() == null ? 0L : request.fileSize());
        record.setDocumentId(documentId);
        record.setParserType(request.parserType() == null ? "TIKA" : request.parserType());
        record.setSplitterType(request.splitterType() == null ? "RECURSIVE" : request.splitterType());
        record.setChunkSize(request.chunkSize());
        record.setOverlap(request.overlap());
        record.setMaxParentSize(request.maxParentSize());
        record.setParentChild(Boolean.TRUE.equals(request.parentChild()) ? 1 : 0);
        record.setChunkCount(chunks.size());
        record.setStatus("SUCCESS");
        documentMapper.insert(record);

        previewStore.remove(documentId);
        log.info("[rag] 入库完成 kbId={} documentId={} chunkCount={}", kb.getId(), documentId, chunks.size());
    }

    /** 删单文档：向量 + 父块 + 记录一起清。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocument(Long id) {
        KnowledgeDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            throw BizException.notFound("文档不存在");
        }
        requireOwnedKb(doc.getKnowledgeBaseId());
        purgeVector(doc.getDocumentId());
        documentMapper.deletePhysically(doc.getKnowledgeBaseId(), doc.getDocumentId());
        log.info("[rag] 删文档 id={} documentId={}", id, doc.getDocumentId());
    }

    // ==================== 检索（给 Agent 用） ====================

    /**
     * 组装检索管道。
     *
     * <p>过滤条件三维：{@code tenantId + userId + knowledgeBaseId} —— 单张向量表靠 metadata 隔离，不分表。
     * 查询增强按 yml 开关，默认全关（照 aicube-robot 的实际取舍：多一次 LLM 调用不划算）。
     */
    public RagPipeline buildPipeline(List<Long> knowledgeBaseIds) {
        // 空列表 = 不限定，检索当前用户自己的全部知识库
        if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
            for (Long kbId : knowledgeBaseIds) {
                requireOwnedKb(kbId);
            }
        }

        FilterExpressionBuilder fb = new FilterExpressionBuilder();
        // 已去掉多租户：只按用户 + 知识库隔离
        var tenantAndUser = fb.eq("userId", String.valueOf(TenantContext.requireUserId()));

        Filter.Expression filter;
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            filter = tenantAndUser.build();
        } else if (knowledgeBaseIds.size() == 1) {
            filter = fb.and(tenantAndUser,
                    fb.eq("knowledgeBaseId", String.valueOf(knowledgeBaseIds.get(0)))).build();
        } else {
            // 🔴 不能用 fb.in()：Spring AI 会渲染成 `$.knowledgeBaseId == [1,2]`，
            // Postgres 的 jsonpath 不认这个方括号写法（syntax error at or near "["），
            // 只能展开成一串 OR：`($.knowledgeBaseId == "1" || $.knowledgeBaseId == "2")`
            var anyKb = fb.eq("knowledgeBaseId", String.valueOf(knowledgeBaseIds.get(0)));
            for (int i = 1; i < knowledgeBaseIds.size(); i++) {
                anyKb = fb.or(anyKb, fb.eq("knowledgeBaseId", String.valueOf(knowledgeBaseIds.get(i))));
            }
            filter = fb.and(tenantAndUser, anyKb).build();
        }

        DefaultRagPipeline.Builder builder = DefaultRagPipeline.builder()
                .documentRetriever(EnhancedRetriever.builder()
                        .vectorStore(ragStores.vectorStore())
                        .documentStore(ragStores.parentDocumentStore())
                        .topK(5)
                        .filterExpression(filter)
                        .enableParentChild(true)
                        .build());

        ZwProperties.QueryEnhance qe = props.getRag().getQueryEnhance();
        if (qe.isCompression()) {
            builder.queryTransformer(QueryTransformers.compression(modelProvider.chatModel()));
        }
        if (qe.isHyde()) {
            builder.queryTransformer(QueryTransformers.hyde(modelProvider.chatModel()));
        }
        if (qe.isMultiQuery()) {
            builder.queryExpander(QueryExpanders.multiQuery(modelProvider.chatModel()));
        }

        ZwProperties.Rerank rerank = props.getRag().getRerank();
        if (rerank.isEnabled() && rerank.getApiKey() != null && !rerank.getApiKey().isBlank()) {
            // 请求体格式跟服务商走：
            //   DashScope 系（百炼 / 千问 MaaS）→ 嵌套 {model, input:{query,documents}, parameters:{top_n}}
            //   OpenRouter / Cohere / Jina → 平铺 {model, query, documents, top_n}
            // 用错格式服务端会直接报参数错误，所以按 base-url 自动判断。
            HttpReranker.RequestFormat fmt = rerank.getBaseUrl() != null
                    && (rerank.getBaseUrl().contains("dashscope")
                        || rerank.getBaseUrl().contains("qianwenaiapi"))
                    ? HttpReranker.RequestFormat.DASHSCOPE
                    : HttpReranker.RequestFormat.FLAT;
            builder.reranker(new HttpReranker(rerank.getBaseUrl(), rerank.getApiKey(),
                    rerank.getModel(), rerank.getTopK(), fmt));
        }
        return builder.build();
    }

    /**
     * 给某次回答创建一个带引用收集的检索工具（每次回答 new 一个，不共享）。
     *
     * @param knowledgeBaseIds 检索范围；空列表 = 这个用户的全部知识库
     */
    public KbSearchTool newSearchTool(List<Long> knowledgeBaseIds, List<Message> chatHistory) {
        return new KbSearchTool(buildPipeline(knowledgeBaseIds), chatHistory);
    }

    /**
     * 服务端强制预检索：在请求进入模型之前先跑一次 kbSearch，把命中的片段
     * 直接拼进本轮 query。
     *
     * <p>为什么需要：像 qwen3.7-flash 这类模型经常无视「先调 kbSearch」的系统提示，
     * 直接凭通用知识作答，导致用户明明选了知识库却拿不到基于文档的回答。
     * 预检索把「用不用知识库」变成服务端行为，不再依赖模型自觉；
     * kbSearch 工具仍然保留，模型可以在此基础上换关键词再检出更多片段。
     *
     * @param searchTool 本轮会话的检索工具（同时承担引用编号登记）
     * @param rawQuery   用户原始问题
     * @param baseQuery  已拼好附件的 query
     * @return 注入检索结果后的 query；检索失败或无命中时原样返回 baseQuery
     */
    public String preRetrieve(KbSearchTool searchTool, String rawQuery, String baseQuery) {
        if (searchTool == null || rawQuery == null || rawQuery.isBlank()) {
            log.info("[rag] 预检索跳过：searchTool={} query为空={}", searchTool == null,
                    rawQuery == null || rawQuery.isBlank());
            return baseQuery;
        }
        log.info("[rag] 预检索开始 query={}", rawQuery);
        String hits;
        try {
            hits = searchTool.prefetch(rawQuery);
        } catch (Exception e) {
            log.warn("[rag] 预检索失败，降级为纯对话: {}", e.getMessage());
            return baseQuery;
        }
        if (hits == null || hits.isBlank() || hits.startsWith("未检索到") || hits.startsWith("检索失败")) {
            log.info("[rag] 预检索无命中，本轮按纯对话处理");
            return baseQuery;
        }
        log.info("[rag] 预检索命中，注入 {} 字片段到上下文", hits.length());
        return baseQuery
                + "\n\n【知识库检索结果（已由系统预先检索，可直接使用）】\n"
                + hits
                + "\n【检索结果结束】\n"
                + "请优先依据上面的片段回答，并在每条结论后用 [片段N] 对应的编号标注来源；"
                + "片段不足以回答时，再调用 kbSearch 换关键词补充检索；仍然没有就明说知识库里没有相关内容。";
    }

    /** 当前用户有没有知识库 —— 决定要不要挂 kbSearch、提示词里要不要加引用硬要求。 */
    public boolean hasAnyKnowledgeBase() {
        Long count = knowledgeBaseMapper.selectCount(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getUserId, TenantContext.requireUserId()));
        return count != null && count > 0;
    }

    public KnowledgeBase requireOwnedKb(Long id) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(id);
        if (kb == null || !TenantContext.requireUserId().equals(kb.getUserId())) {
            throw BizException.forbidden("无权操作该知识库");
        }
        return kb;
    }

    // ==================== 私有 ====================

    private void purgeVector(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return;
        }
        ragStores.documentStore().deleteByDocumentId(documentId);
    }

    private List<Document> parseAndSplit(byte[] content, String fileName, String parser,
                                         String splitter, int chunkSize, int overlap,
                                         int maxParentSize, int headingLevel, boolean parentChild,
                                         boolean enableImageUnderstanding) {
        try {
            RawDocument raw = new RawDocument(fileName, content, Map.of());
            List<Document> parsed = "MINERU".equalsIgnoreCase(parser)
                    ? parseWithMineru(raw, enableImageUnderstanding)
                    : new TikaDocumentParser().parseToDocuments(raw);
            if (parsed.isEmpty()) {
                throw BizException.badRequest("解析结果为空，可能是扫描件（图片型 PDF），请改用 MinerU 解析器");
            }
            List<Document> split = buildSplitter(splitter, chunkSize, overlap, maxParentSize, headingLevel, parentChild)
                    .split(parsed);
            // 兜底硬切：MinerU 把整页表格识别成一个 <table> HTML 块时，里面几乎没有换行/标点，
            // RecursiveSplitter 找不到切分点会整块返回（实测出现过 28KB 单块），
            // 送去 embedding 会报 "Tokens in a single document exceeds the maximum..."。
            // 这里按字符硬切，保证每块不超过 chunkSize 的安全上限。
            return enforceMaxChars(split, chunkSize);
        } catch (StackOverflowError e) {
            throw BizException.badRequest("文档解析失败：文件过大或结构过于复杂，请拆分后重试");
        }
    }

    /** 单块字符上限的硬性兜底倍数（splitter 正常切分时不会触发） */
    private static final int HARD_SPLIT_FACTOR = 4;

    /**
     * 保证没有任何一块超过 {@code chunkSize * HARD_SPLIT_FACTOR} 字符。
     * 超长块按字符切分，元数据（含图片 assetId、contentType）原样继承到每个子块上。
     */
    private static List<Document> enforceMaxChars(List<Document> docs, int chunkSize) {
        int limit = Math.max(chunkSize * HARD_SPLIT_FACTOR, 1200);
        boolean needFix = docs.stream()
                .anyMatch(d -> d.getText() != null && d.getText().length() > limit);
        if (!needFix) {
            return docs;
        }
        List<Document> out = new ArrayList<>(docs.size() * 2);
        for (Document doc : docs) {
            String text = doc.getText();
            if (text == null || text.length() <= limit) {
                out.add(doc);
                continue;
            }
            int seq = 0;
            for (int i = 0; i < text.length(); i += limit) {
                String part = text.substring(i, Math.min(text.length(), i + limit));
                Map<String, Object> meta = new HashMap<>(doc.getMetadata());
                meta.put("hardSplit", true);
                meta.put("hardSplitSeq", seq);
                meta.put("hardSplitFrom", doc.getId());
                // 向量库要求 id 是合法 UUID：不能用 "原id-h1" 这种拼接。
                // 用「原文 + 序号」重新生成确定性 UUID，保证同内容重复入库 id 稳定。
                String newId = UUID.nameUUIDFromBytes(
                        (doc.getId() + "#" + seq).getBytes(StandardCharsets.UTF_8)).toString();
                seq++;
                out.add(new Document(newId, part, meta));
            }
        }
        return out;
    }

    /**
     * MinerU 解析。开启图片语义理解时要求有多模态模型（对齐 aicube-robot 的行为：
     * 开了但没配模型直接 400，不要静默降级成"没理解图片"）。
     */
    private List<Document> parseWithMineru(RawDocument raw, boolean enableImageUnderstanding) {
        if (MINERU_REJECTED_EXT.contains(extensionOf(raw.fileName()))) {
            throw BizException.badRequest("MinerU 解析不支持 Markdown（.md）文件，请把解析器切回 TIKA");
        }
        String token = props.getRag().getMineru().getToken();
        if (token == null || token.isBlank()) {
            throw BizException.badRequest("未配置 MinerU token，请改用 TIKA 解析器");
        }

        // 图片资产落本地磁盘：assetDir/{documentId}/{图片名}
        String assetDir = props.getRag().getMineru().getAssetDir();
        MineruDocumentParser.Builder builder = MineruDocumentParser.builder()
                .authToken(token)
                .assetStore(new LocalDocumentAssetStore(Path.of(assetDir)));
        log.info("[rag] MinerU 图片资产目录: {}", Path.of(assetDir).toAbsolutePath());
        if (enableImageUnderstanding) {
            org.springframework.ai.chat.model.ChatModel imageModel = modelProvider.multimodalModel();
            if (imageModel == null) {
                throw BizException.badRequest("未配置可用的多模态模型，无法开启图片理解");
            }
            log.info("[rag] 图片理解已启用，多模态模型={}", imageModel.getClass().getSimpleName());
            builder.enableImageUnderstanding(true).imageChatModel(imageModel);
        }
        return builder.build().parseToDocuments(raw);
    }

    private TextSplitter buildSplitter(String splitter, int chunkSize, int overlap,
                                       int maxParentSize, int headingLevel, boolean parentChild) {
        if ("HEADING".equalsIgnoreCase(splitter)) {
            return HeadingSplitter.builder()
                    .maxHeadingLevel(headingLevel)
                    .chunkSize(chunkSize)
                    .overlap(overlap)
                    .maxParentSize(maxParentSize)
                    .enableParentChild(parentChild)
                    .build();
        }
        if ("RECURSIVE".equalsIgnoreCase(splitter)) {
            return RecursiveSplitter.builder()
                    .chunkSize(chunkSize)
                    .overlap(overlap)
                    .build();
        }
        return SlidingWindowSplitter.builder()
                .chunkSize(chunkSize)
                .overlap(overlap)
                .build();
    }

    private void checkFileSize(MultipartFile file) {
        long maxBytes = props.getRag().getUpload().getMaxFileSizeMb() * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw BizException.badRequest("文件过大（" + (file.getSize() / 1024 / 1024) + "MB），最大 "
                    + props.getRag().getUpload().getMaxFileSizeMb() + "MB");
        }
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    private int countByRole(List<Document> chunks, ChunkRole role) {
        return (int) chunks.stream()
                .filter(c -> role.code().equals(c.getMetadata().get(MetadataKeys.CHUNK_ROLE)))
                .count();
    }

    private String roleOf(Document doc) {
        Object role = doc.getMetadata().get(MetadataKeys.CHUNK_ROLE);
        return role == null ? "chunk" : role.toString();
    }
}
