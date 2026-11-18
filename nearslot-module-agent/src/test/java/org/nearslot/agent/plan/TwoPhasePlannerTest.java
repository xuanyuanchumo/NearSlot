package org.nearslot.agent.plan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.common.api.*;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 两阶段规划器单测（FR-5.1：规划不执行、轮次受限、真实契约取数）。 */
class TwoPhasePlannerTest {

    private SlotAvailabilityQuery slotQuery;
    private ShopRecommendQuery shopQuery;
    private OrderQuery orderQuery;
    private TechRankQuery rankQuery;
    private TwoPhasePlanner planner;

    @BeforeEach
    void setUp() {
        slotQuery = mock(SlotAvailabilityQuery.class);
        shopQuery = mock(ShopRecommendQuery.class);
        orderQuery = mock(OrderQuery.class);
        rankQuery = mock(TechRankQuery.class);
        planner = new TwoPhasePlanner(new org.nearslot.agent.config.AgentProperties(), slotQuery, shopQuery, orderQuery, rankQuery);
    }

    @Test
    @DisplayName("PLAN 阶段只产步骤，不调用任何契约（无副作用）")
    void planDoesNotExecute() {
        var plan = planner.plan("我想预约明天下午的推拿");
        assertFalse(plan.isEmpty());
        verifyNoInteractions(slotQuery, shopQuery, orderQuery, rankQuery);
    }

    @Test
    @DisplayName("查询订单意图 -> 计划含 queryMyAppointments")
    void orderIntent() {
        var plan = planner.plan("查看我的订单");
        assertTrue(plan.steps().stream().anyMatch(s -> s.tool().equals("queryMyAppointments")));
    }

    @Test
    @DisplayName("热门意图 -> 计划含 topServiceStaff")
    void rankIntent() {
        var plan = planner.plan("今天有哪些热门技师");
        assertTrue(plan.steps().stream().anyMatch(s -> s.tool().equals("topServiceStaff")));
    }

    @Test
    @DisplayName("EXECUTE 走真实契约取数")
    void executeUsesContracts() {
        when(orderQuery.listByUser(anyLong(), anyInt())).thenReturn(List.of());
        var step = new TwoPhasePlanner.PlanStep(1, "queryMyAppointments", "查询我的预约");
        String r = planner.execute(step, 1001L, "查看我的订单");
        assertTrue(r.contains("暂无预约"));
        verify(orderQuery).listByUser(1001L, 5);
    }

    @Test
    @DisplayName("契约抛异常 -> 降级话术，不向上冒泡")
    void executeHandlesFailure() {
        when(slotQuery.available(anyLong(), any(), any()))
            .thenThrow(new RuntimeException("redis down"));
        var step = new TwoPhasePlanner.PlanStep(1, "querySlots", "查询时段");
        String r = planner.execute(step, 1001L, "查时段");
        assertTrue(r.contains("查询失败"));
    }

    @Test
    @DisplayName("轮次上限约束生效")
    void roundLimit() {
        // 上限来自 `nearslot.agent.max-tool-rounds`，测试按默认值断言
        int limit = new org.nearslot.agent.config.AgentProperties().getMaxToolRounds();
        assertTrue(planner.withinLimit(limit));
        assertFalse(planner.withinLimit(limit + 1));
    }

    @Test
    @DisplayName("空输入 -> 空计划")
    void emptyInput() {
        assertTrue(planner.plan("").isEmpty());
        assertTrue(planner.plan(null).isEmpty());
    }
}
