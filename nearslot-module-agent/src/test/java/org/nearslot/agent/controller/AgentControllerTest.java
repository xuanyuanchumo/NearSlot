package org.nearslot.agent.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.agent.guard.CommandGuard;
import org.nearslot.agent.guard.ConfirmTokenService;
import org.nearslot.agent.plan.TwoPhasePlanner;
import org.nearslot.common.api.*;
import org.nearslot.common.context.UserHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Agent 控制器单测（FR-5.1/5.2）。
 *
 * 直接调用方法而非 MockMvc：SSE 是异步流，用 SseEmitter 的 handler 捕获事件更直观。
 */
class AgentControllerTest {

    private CommandGuard guard;
    private ConfirmTokenService tokenService;
    private TwoPhasePlanner planner;
    private AgentController controller;

    @BeforeEach
    void setUp() {
        guard = mock(CommandGuard.class);
        tokenService = mock(ConfirmTokenService.class);
        planner = mock(TwoPhasePlanner.class);
        controller = new AgentController(guard, tokenService, planner, new org.nearslot.agent.config.AgentProperties());
        UserHolder.set(1001L);
    }

    @AfterEach
    void tearDown() { UserHolder.clear(); }

    @Test
    @DisplayName("BLOCK 指令 -> 只发 meta + error，不调 planner")
    void blockDecision() throws Exception {
        when(guard.decide("删除所有")).thenReturn(CommandGuard.Decision.BLOCK);
        when(guard.blockReason()).thenReturn("不允许");

        SseEmitter em = controller.chat(Map.of("message", "删除所有"));
        assertNotNull(em);
        verify(planner, never()).plan(anyString());
        verifyNoInteractions(tokenService);
    }

    @Test
    @DisplayName("CONFIRM 指令 -> 签发令牌，不调 planner")
    void confirmDecision() {
        when(guard.decide("取消预约")).thenReturn(CommandGuard.Decision.CONFIRM);
        when(tokenService.issue(eq(1001L), anyString())).thenReturn("tok");

        SseEmitter em = controller.chat(Map.of("message", "取消预约"));
        assertNotNull(em);
        verify(tokenService).issue(eq(1001L), anyString());
        verify(planner, never()).plan(anyString());
    }

    @Test
    @DisplayName("ALLOW 指令 -> 生成计划并执行每一步")
    void allowDecisionExecutesPlan() {
        when(guard.decide(anyString())).thenReturn(CommandGuard.Decision.ALLOW);
        var step = new TwoPhasePlanner.PlanStep(1, "queryMyAppointments", "查询");
        when(planner.plan(anyString())).thenReturn(new TwoPhasePlanner.Plan(List.of(step)));
        when(planner.execute(any(), eq(1001L), anyString())).thenReturn("您暂无预约");

        SseEmitter em = controller.chat(Map.of("message", "查订单"));
        assertNotNull(em);
        verify(planner).execute(eq(step), eq(1001L), eq("查订单"));
    }

    @Test
    @DisplayName("空计划 -> 正常结束，不执行任何步")
    void emptyPlan() {
        when(guard.decide(anyString())).thenReturn(CommandGuard.Decision.ALLOW);
        when(planner.plan(anyString())).thenReturn(new TwoPhasePlanner.Plan(List.of()));

        SseEmitter em = controller.chat(Map.of("message", "嗯"));
        assertNotNull(em);
        verify(planner, never()).execute(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("confirm 端点 -> 委托令牌服务消费")
    void confirmEndpointDelegates() {
        when(tokenService.consume(1001L, "tok")).thenReturn(true);
        var res = controller.confirm(Map.of("confirmToken", "tok"));
        assertEquals(Boolean.TRUE, res.getData());
        verify(tokenService).consume(1001L, "tok");
    }

    @Test
    @DisplayName("confirm 端点：无令牌 -> 服务返回 false")
    void confirmWithoutToken() {
        when(tokenService.consume(eq(1001L), isNull())).thenReturn(false);
        var res = controller.confirm(Map.of());
        assertEquals(Boolean.FALSE, res.getData());
    }
}
