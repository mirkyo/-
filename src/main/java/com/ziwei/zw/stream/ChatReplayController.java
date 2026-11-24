package com.ziwei.zw.stream;

import com.ziwei.zw.common.R;
import com.ziwei.zw.session.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话回放：从框架的 {@code agentx_trace}（LLM/工具调用审计）重建「思考过程 + 工具调用」，
 * 叠加 {@code zw_citation}（本项目自存的引用来源）。
 *
 * <p>为什么需要它：{@code agentx_session} 只存对话消息正文；思考/工具在 {@code agentx_trace}
 * （需 {@code app.agent.enable-trace=true}），来源卡片是本项目扩展、存在 {@code zw_citation}。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatReplayController {

    private final JdbcTemplate jdbc;
    private final SessionService sessionService;
    private final CitationStore citationStore;

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * agentx_trace 里的 {@code input_data} / {@code output_data} 存的是 JSON 序列化后的字符串字面量
     * （外层带引号，换行是 {@code \n} 两个字符）。原样透传给前端的话，命中片段拆不出段落，
     * 正文里也会看到 "\n" 字面量 —— 这里反转义成真实字符串。
     */
    private static String unquote(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.length() > 1 && s.startsWith("\"") && s.endsWith("\"")) {
            try {
                return JSON.readValue(s, String.class);
            } catch (Exception e) {
                return raw;
            }
        }
        return raw;
    }

    /**
     * 回放某个会话的完整过程。
     *
     * @return {conversationId, messages, turns:[{turn, rounds:[{round,think,tools}], citations:[...]}]}
     */
    @GetMapping("/replay/{conversationId}")
    public R<Map<String, Object>> replay(@PathVariable String conversationId) {
        sessionService.requireOwned(conversationId);

        List<Map<String, String>> messages = sessionService.messages(conversationId);

        // session_id（= agentx_session.id，一次调用的记录）代表「第几轮问答」
        // session_id -> (round -> {round, think, tools})
        Map<Long, Map<Integer, Map<String, Object>>> bySession = new LinkedHashMap<>();

        try {
            // 必须显式用 RowCallbackHandler：三参 query 若推断成 ResultSetExtractor
            // 只会回调一次且不移动游标 ⇒ 一行都读不到
            RowCallbackHandler handler = rs -> {
                long sessionId = rs.getLong("session_id");
                int round = rs.getInt("round");
                Map<Integer, Map<String, Object>> rounds =
                        bySession.computeIfAbsent(sessionId, k -> new LinkedHashMap<>());
                Map<String, Object> bucket = rounds.computeIfAbsent(round, r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("round", r);
                    m.put("think", "");
                    m.put("tools", new ArrayList<Map<String, Object>>());
                    return m;
                });
                String span = rs.getString("span_type");
                if ("TOOL".equalsIgnoreCase(span)) {
                    Map<String, Object> tool = new LinkedHashMap<>();
                    tool.put("name", rs.getString("tool_name"));
                    tool.put("toolCallId", rs.getString("tool_call_id"));
                    tool.put("args", unquote(rs.getString("input_data")));
                    tool.put("result", unquote(rs.getString("output_data")));
                    tool.put("durationMs", rs.getLong("duration_ms"));
                    tool.put("success", rs.getInt("success") == 1);
                    tool.put("error", rs.getString("error_message"));
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> tools = (List<Map<String, Object>>) bucket.get("tools");
                    tools.add(tool);
                } else if ("LLM".equalsIgnoreCase(span)) {
                    String think = rs.getString("think");
                    if (think != null && !think.isBlank()) {
                        String old = (String) bucket.get("think");
                        bucket.put("think", old == null || old.isBlank() ? think : old + think);
                    }
                }
            };
            jdbc.query(
                    "SELECT session_id, round, span_type, tool_name, tool_call_id, input_data, output_data, "
                            + "think, duration_ms, success, error_message "
                            + "FROM agentx_trace WHERE conversation_id = ? ORDER BY session_id, round, id",
                    handler, conversationId);
        } catch (Exception e) {
            log.warn("[replay] 读取 agentx_trace 失败 cid={}: {}", conversationId, e.getMessage());
        }

        Map<Integer, List<Map<String, Object>>> citations = citationStore.loadByTurn(conversationId);

        List<Map<String, Object>> turns = new ArrayList<>();
        int turnNo = 1;
        for (Map<Integer, Map<String, Object>> rounds : bySession.values()) {
            Map<String, Object> turn = new LinkedHashMap<>();
            turn.put("turn", turnNo);
            turn.put("rounds", new ArrayList<>(rounds.values()));
            turn.put("citations", citations.getOrDefault(turnNo, List.of()));
            turns.add(turn);
            turnNo++;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", conversationId);
        data.put("messages", messages);
        data.put("turns", turns);
        log.info("[replay] cid={} 消息 {} 条 / 问答 {} 轮 / 引用轮次 {}",
                conversationId, messages.size(), turns.size(), citations.size());
        return R.ok(data);
    }
}
