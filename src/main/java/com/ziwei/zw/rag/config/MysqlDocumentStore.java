package com.ziwei.zw.rag.config;

import com.agentx.ai.rag.store.DocumentStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 父块存储的 MySQL 实现。
 *
 * <p>框架自带的 {@code JdbcDocumentStore} 用的是 PostgreSQL 语法
 * （{@code ON CONFLICT ... DO UPDATE}、{@code CREATE INDEX IF NOT EXISTS}），
 * 在 MySQL 上会直接语法错误，所以 Windows 部署（无 PG）时用这个替代。
 *
 * <p>表结构与框架版保持一致：{@code chunk_id / document_id / text / metadata}。
 */
@Slf4j
public class MysqlDocumentStore implements DocumentStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public MysqlDocumentStore(DataSource dataSource, String table) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                + "chunk_id VARCHAR(100) NOT NULL PRIMARY KEY, "
                + "document_id VARCHAR(200) NOT NULL, "
                + "text MEDIUMTEXT NOT NULL, "
                + "metadata MEDIUMTEXT NULL"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        // MySQL 的 CREATE INDEX 不支持 IF NOT EXISTS，索引已存在时忽略异常
        try {
            this.jdbc.execute("CREATE INDEX idx_" + table + "_doc ON " + table + " (document_id)");
        } catch (Exception ignore) {
            // 索引已存在
        }
        this.table = table;
        log.info("[rag] 父块表就绪（MySQL）: {}", table);
    }

    private final String table;

    @Override
    public void save(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO " + table + " (chunk_id, document_id, text, metadata) VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE document_id = VALUES(document_id), text = VALUES(text), "
                + "metadata = VALUES(metadata)";
        List<Object[]> batch = new ArrayList<>(documents.size());
        for (Document d : documents) {
            Object docId = d.getMetadata().get("documentId");
            batch.add(new Object[]{
                    d.getId(),
                    docId == null ? "" : String.valueOf(docId),
                    d.getText(),
                    writeMetadata(d.getMetadata())
            });
        }
        jdbc.batchUpdate(sql, batch);
    }

    @Override
    public Document get(String chunkId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT chunk_id, text, metadata FROM " + table + " WHERE chunk_id = ?", chunkId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        String text = row.get("text") == null ? "" : String.valueOf(row.get("text"));
        return new Document(String.valueOf(row.get("chunk_id")), text, readMetadata(row.get("metadata")));
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        jdbc.update("DELETE FROM " + table + " WHERE document_id = ?", documentId);
    }

    private String writeMetadata(Map<String, Object> metadata) {
        try {
            return JSON.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (Exception e) {
            log.warn("[rag] 父块 metadata 序列化失败: {}", e.getMessage());
            return "{}";
        }
    }

    private Map<String, Object> readMetadata(Object raw) {
        if (raw == null) {
            return new HashMap<>();
        }
        try {
            return JSON.readValue(String.valueOf(raw), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
