package org.nearslot.agent.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.agent.guard.CommandGuard;
import org.nearslot.agent.guard.ConfirmTokenService;
import org.nearslot.agent.plan.TwoPhasePlanner;
import org.nearslot.agent.sse.SseEvent;
import org.nearslot.common.Result;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.context.UserHolder;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI 助手 SSE 接口（FR-5.1/5.2，Phase 3 骨架）。
 *
 * 流程：闸口判定 -> BLOCK 直接 error / CONFIRM 下发 confirm / ALLOW 进 PLAN -> progress
 * 当前为可运行骨架：不接真实 LLM（Phase 3 后续接 Spring AI ChatClient），
 * 但闸口、计划、事件契约已全部生效且有测试覆盖。
 */
@Slf4j
@Tag(name = "AI 助手", description = "自然语言预约/查询（FR-5.1/5.2）")
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentController {

    private final CommandGuard guard;
    private final ConfirmTokenService confirmTokenService;
    private final TwoPhasePlanner planner;

    @Operation(summary = "流式对话", description = "SSE：meta/plan/progress/confirm/error 五类事件")
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody Map<String, String> body) {
        String input = body == null ? null : body.get("message");
        Long userId = UserHolder.get();
        SseEmitter emitter = new SseEmitter(60_000L);
        AtomicLong seq = new AtomicLong();

        try {
            CommandGuard.Decision d = guard.decide(input);
            emitter.send(SseEvent.of("meta", seq.incrementAndGet(),
                Map.of("userId", String.valueOf(userId), "decision", d.name())));

            if (d == CommandGuard.Decision.BLOCK) {
                emitter.send(SseEvent.error(seq.incrementAndGet(),
                    String.valueOf(BaseCode.AGENT_BLOCKED.getCode()),
                    guard.blockReason(), false));
                emitter.complete();
                return emitter;
            }
            if (d == CommandGuard.Decision.CONFIRM) {
                String token = confirmTokenService.issue(userId, String.valueOf(input));
                emitter.send(SseEvent.confirm(seq.incrementAndGet(), token,
                    BaseCode.AGENT_NEED_CONFIRM.getMessage() + "：" + input,
                    ConfirmTokenService.TTL_SECONDS));
                emitter.complete();
                return emitter;
            }

            TwoPhasePlanner.Plan plan = planner.plan(input);
            List<String> stepTexts = plan.steps().stream()
                .map(s -> s.index() + ". " + s.purpose()).toList();
            emitter.send(SseEvent.plan(seq.incrementAndGet(), stepTexts));

            for (TwoPhasePlanner.PlanStep step : plan.steps()) {
                String result = planner.execute(step, userId, input);
                emitter.send(SseEvent.progress(seq.incrementAndGet(), step.tool(), result));
            }
            emitter.complete();
        } catch (Exception e) {
            log.error("SSE 对话失败", e);
            try {
                emitter.send(SseEvent.error(seq.incrementAndGet(),
                    String.valueOf(BaseCode.AGENT_UPSTREAM_ERROR.getCode()),
                    BaseCode.AGENT_UPSTREAM_ERROR.getMessage(), true));
            } catch (Exception ignored) {
                // 客户端已断开，忽略
            }
            emitter.completeWithError(e);
        }
        return emitter;
    }

    @Operation(summary = "确认敏感操作", description = "携带 confirmToken 执行被挂起的操作")
    @PostMapping("/confirm")
    public Result<Boolean> confirm(@RequestBody Map<String, String> body) {
        Long userId = UserHolder.get();
        String token = body == null ? null : body.get("confirmToken");
        return Result.ok(confirmTokenService.consume(userId, token));
    }
}
