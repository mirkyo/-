package com.ziwei.zw.stream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 进行中对话流的注册表 —— 断点续传的地基。
 *
 * <p>照抄 aicube-robot {@code AgentServiceImpl} 里 {@code liveChats} 那套，三个关键点缺一不可：
 * <ol>
 *   <li>{@code index(...)} 给每个事件打**自增 id**，前端据此记录游标、去重补发重叠；</li>
 *   <li>{@code replay()} 把流变成 {@code ConnectableFlux}，新订阅者能拿到**已产出的全部事件**；</li>
 *   <li>注册时**立刻 {@code connect()}**，让执行由后台驱动 ——
 *       HTTP 断开只是取消 HTTP 层订阅，**不会**把取消传播给 Agent 执行。</li>
 * </ol>
 *
 * <p>流终止时按 key+value 摘除，避免误删同一 conversationId 的下一轮注册项。
 */
@Slf4j
@Component
public class LiveStreamRegistry {

    private final Map<String, LiveChat> liveChats = new ConcurrentHashMap<>();

    private record LiveChat(ConnectableFlux<ServerSentEvent<Object>> replay,
                            AtomicReference<Disposable> connection,
                            String userId) {
    }

    /**
     * 注册一条进行中的流：事件编号 + 重放缓存 + 后台驱动。
     *
     * @param source 原始事件流（框架事件 + 我们追加的 Citations 事件）
     */
    public Flux<ServerSentEvent<Object>> registerStream(String conversationId, String userId,
                                                        Flux<Object> source) {
        AtomicReference<LiveChat> entryRef = new AtomicReference<>();
        ConnectableFlux<ServerSentEvent<Object>> replay = source
                .index((i, evt) -> ServerSentEvent.builder(evt).id(String.valueOf(i + 1)).build())
                .doFinally(signal -> {
                    LiveChat entry = entryRef.get();
                    if (entry != null) {
                        liveChats.remove(conversationId, entry);
                        log.info("[stream] 流结束摘除 convId={} signal={}", conversationId, signal);
                    }
                })
                .replay();

        LiveChat live = new LiveChat(replay, new AtomicReference<>(), userId);
        entryRef.set(live);
        liveChats.put(conversationId, live);

        // 立刻后台驱动：HTTP 断开只取消 HTTP 层订阅，Agent 执行不受影响
        replay.connect(live.connection()::set);
        log.info("[stream] 注册进行中流 convId={} userId={}", conversationId, userId);
        return replay;
    }

    /**
     * 从断点接回：跳过 id 小于 startSeq 的事件，先重放历史再续看实时尾流。
     *
     * @return null 表示该会话没有进行中的流
     */
    public Flux<ServerSentEvent<Object>> attach(String conversationId, Long startSeq) {
        LiveChat live = liveChats.get(conversationId);
        if (live == null) {
            return null;
        }
        long from = Math.max(startSeq == null ? 0 : startSeq, 1);
        return live.replay().skipUntil(sse -> Long.parseLong(sse.id()) >= from);
    }

    public boolean isActive(String conversationId) {
        return liveChats.containsKey(conversationId);
    }

    /** 进行中流的属主（无进行中流返回 null）。 */
    public String ownerOf(String conversationId) {
        LiveChat live = liveChats.get(conversationId);
        return live == null ? null : live.userId();
    }
}
