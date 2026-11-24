package com.ziwei.zw.rag.store;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 切块预览暂存。
 *
 * <p>aicube-robot 用 Redis 存这个；我们按「MySQL + PG，不引 Redis」的口径改用 Caffeine，
 * 进程内缓存、30 分钟过期、最多 200 份。重启会丢，重新上传预览即可。
 */
@Slf4j
@Component
public class ChunkPreviewStore {

    private final Cache<String, List<Document>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(30))
            .maximumSize(200)
            .build();

    public void save(String documentId, List<Document> chunks) {
        cache.put(documentId, chunks);
        log.debug("[rag] 暂存切块 documentId={} count={}", documentId, chunks.size());
    }

    public List<Document> get(String documentId) {
        return cache.getIfPresent(documentId);
    }

    public void remove(String documentId) {
        cache.invalidate(documentId);
    }
}
