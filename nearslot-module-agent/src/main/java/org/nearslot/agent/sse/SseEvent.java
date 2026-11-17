package org.nearslot.agent.sse;

import java.util.List;
import java.util.Map;

/**
 * SSE 事件契约（FR-5.2 / ADR-023）。
 *
 * 五类事件，前端按 event 字段分发：
 *   meta     - 会话元信息（conversationId）
 *   plan     - 两阶段规划的 PLAN 产物（待执行步骤，只读展示）
 *   progress - 工具调用进度（tool/status）
 *   confirm  - 敏感操作二次确认（TTL 300s）
 *   error    - 错误（code/message/retryable）
 *
 * 设计原则：事件体扁平 JSON，避免前端深层解析；
 * 每个事件带 seq，便于断线重连时按 Last-Event-ID 续传。
 */
public record SseEvent(String event, long seq, Map<String, Object> data) {

    public static SseEvent of(String event, long seq, Map<String, Object> data) {
        return new SseEvent(event, seq, data);
    }

    public static SseEvent meta(long seq, String conversationId) {
        return of("meta", seq, Map.of("conversationId", conversationId));
    }

    public static SseEvent plan(long seq, List<String> steps) {
        return of("plan", seq, Map.of("steps", steps));
    }

    public static SseEvent progress(long seq, String tool, String status) {
        return of("progress", seq, Map.of("tool", tool, "status", status));
    }

    public static SseEvent confirm(long seq, String token, String summary, int ttlSeconds) {
        return of("confirm", seq, Map.of(
            "confirmToken", token, "summary", summary, "ttlSeconds", ttlSeconds));
    }

    public static SseEvent error(long seq, String code, String message, boolean retryable) {
        return of("error", seq, Map.of(
            "code", code, "message", message, "retryable", retryable));
    }
}
