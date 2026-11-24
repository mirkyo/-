package com.ziwei.zw.stream;

import com.ziwei.zw.rag.tool.Citation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引用来源落库（{@code zw_citation}）。
 *
 * <p>为什么要单独存：{@code agentx_trace} 只记 LLM/工具调用审计，
 * 而「来源卡片」是本项目自己的扩展，框架里没有对应存储。
 *
 * <p>按 {@code turn}（第几轮问答）分组保存，多轮对话每一轮的来源都能回放。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CitationStore {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS zw_citation (
                id              BIGINT       NOT NULL AUTO_INCREMENT,
                conversation_id VARCHAR(100) NOT NULL COMMENT '会话ID',
                turn            INT          NOT NULL DEFAULT 1 COMMENT '第几轮问答（从1开始）',
                no              INT          NOT NULL COMMENT '正文角标编号',
                file_name       VARCHAR(255) DEFAULT NULL,
                heading_path    VARCHAR(512) DEFAULT NULL,
                chunk_id        VARCHAR(128) DEFAULT NULL,
                document_id     VARCHAR(128) DEFAULT NULL,
                content         LONGTEXT     DEFAULT NULL COMMENT '片段原文',
                created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_zw_citation_conv (conversation_id, turn)
            ) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='回答引用来源（按轮次保存）'
            """;

    private final JdbcTemplate jdbc;
    private volatile boolean ready = false;

    @PostConstruct
    void init() {
        try {
            jdbc.execute(DDL);
            // 老表补 turn 列（已存在会抛异常，忽略即可）
            try {
                jdbc.execute("ALTER TABLE zw_citation ADD COLUMN turn INT NOT NULL DEFAULT 1 AFTER conversation_id");
                log.info("[citation] 已为 zw_citation 补充 turn 列");
            } catch (Exception ignore) {
                // 列已存在
            }
            ready = true;
            log.info("[citation] zw_citation 表就绪");
        } catch (Exception e) {
            log.warn("[citation] 建表失败，引用将不落库: {}", e.getMessage());
        }
    }

    /** 该会话的下一轮序号（1 开始） */
    public int nextTurn(String conversationId) {
        if (!ready || conversationId == null || conversationId.isBlank()) {
            return 1;
        }
        try {
            Integer max = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(turn), 0) FROM zw_citation WHERE conversation_id = ?",
                    Integer.class, conversationId);
            return (max == null ? 0 : max) + 1;
        } catch (Exception e) {
            return 1;
        }
    }

    /** 保存某一轮的引用（先清掉该轮旧记录再插入，幂等） */
    public void saveTurn(String conversationId, int turn, List<Citation> citations) {
        if (!ready || conversationId == null || conversationId.isBlank()
                || citations == null || citations.isEmpty()) {
            return;
        }
        try {
            jdbc.update("DELETE FROM zw_citation WHERE conversation_id = ? AND turn = ?",
                    conversationId, turn);
            for (Citation c : citations) {
                jdbc.update("INSERT INTO zw_citation (conversation_id, turn, no, file_name, heading_path, "
                                + "chunk_id, document_id, content) VALUES (?,?,?,?,?,?,?,?)",
                        conversationId, turn, c.no(), c.fileName(), c.headingPath(),
                        c.chunkId(), c.documentId(), c.text());
            }
        } catch (Exception e) {
            log.warn("[citation] 保存引用失败 cid={} turn={}: {}", conversationId, turn, e.getMessage());
        }
    }

    /** 读回该会话全部引用，按轮次分组：turn -> [{no,fileName,headingPath,text}] */
    public Map<Integer, List<Map<String, Object>>> loadByTurn(String conversationId) {
        Map<Integer, List<Map<String, Object>>> byTurn = new LinkedHashMap<>();
        if (!ready || conversationId == null || conversationId.isBlank()) {
            return byTurn;
        }
        try {
            jdbc.query("SELECT turn, no, file_name, heading_path, content FROM zw_citation "
                            + "WHERE conversation_id = ? ORDER BY turn, no",
                    rs -> {
                        int turn = rs.getInt("turn");
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("no", rs.getInt("no"));
                        m.put("fileName", rs.getString("file_name"));
                        m.put("headingPath", rs.getString("heading_path"));
                        m.put("text", rs.getString("content"));
                        byTurn.computeIfAbsent(turn, k -> new ArrayList<>()).add(m);
                    },
                    conversationId);
        } catch (Exception e) {
            log.warn("[citation] 读取引用失败 cid={}: {}", conversationId, e.getMessage());
        }
        return byTurn;
    }
}
