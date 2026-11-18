package org.nearslot.agent.plan;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.common.api.*;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 两阶段规划器（FR-5.1 / ADR-023）。
 *
 * PLAN 阶段：只产出「要做什么」的步骤清单，不执行工具。
 * EXECUTE 阶段：逐条执行并通过 progress 事件回报。
 *
 * 跨模块数据全部走 common 契约（C-ARCH-01），agent 不 import 任何 module。
 * 业务校验不在此处重复：余量/等级/冲突由 provider 的 Lua 三检查裁决。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TwoPhasePlanner {

    /** 单次对话允许的最大工具执行轮次（research/04 实证值）。 */
    /** 最大工具轮数外置到 `nearslot.agent.max-tool-rounds`（防 token 成本失控）。 */

    private final org.nearslot.agent.config.AgentProperties props;
    private final SlotAvailabilityQuery slotAvailabilityQuery;
    private final ShopRecommendQuery shopRecommendQuery;
    private final OrderQuery orderQuery;
    private final TechRankQuery techRankQuery;

    /** 计划中的一步。 */
    public record PlanStep(int index, String tool, String purpose) {}

    /** 完整计划。 */
    public record Plan(List<PlanStep> steps) {
        public boolean isEmpty() { return steps == null || steps.isEmpty(); }
    }

    /** 构建计划（PLAN 阶段，纯数据，不执行）。 */
    public Plan plan(String userInput) {
        List<PlanStep> steps = new ArrayList<>();
        if (userInput == null || userInput.isBlank()) return new Plan(steps);

        boolean asksShop = userInput.contains("服务商") || userInput.contains("店铺") || userInput.contains("附近");
        boolean asksSlot = userInput.contains("时段") || userInput.contains("时间") || userInput.contains("预约");
        boolean asksOrder = userInput.contains("订单") || userInput.contains("我的预约");
        boolean asksRank = userInput.contains("热门") || userInput.contains("排行") || userInput.contains("推荐");

        int i = 1;
        if (asksShop) steps.add(new PlanStep(i++, "recommendShops", "查询附近服务商"));
        if (asksSlot) steps.add(new PlanStep(i++, "querySlots", "查询可预约时段"));
        if (asksOrder) steps.add(new PlanStep(i++, "queryMyAppointments", "查询我的预约"));
        if (asksRank) steps.add(new PlanStep(i++, "topServiceStaff", "查询热门服务者"));

        if (steps.size() > props.getMaxToolRounds()) steps = steps.subList(0, props.getMaxToolRounds());
        log.debug("生成计划 steps={}", steps.size());
        return new Plan(steps);
    }

    /**
     * 执行单步（EXECUTE 阶段）。返回可读文本用于 progress 事件。
     * 真实数据全部来自 common 契约。
     */
    public String execute(PlanStep step, Long userId, String userInput) {
        try {
            return switch (step.tool()) {
                case "recommendShops" -> {
                    var shops = shopRecommendQuery.page(1, 5, null);
                    yield shops.isEmpty() ? "暂无服务商" :
                        "找到 " + shops.size() + " 家：" + shops.stream()
                            .map(ShopRecommendQuery.ShopView::name).toList();
                }
                case "querySlots" -> {
                    var slots = slotAvailabilityQuery.available(1L, LocalDate.now().plusDays(1), null);
                    yield slots.isEmpty() ? "明日暂无可约时段" :
                        "明日可约 " + slots.size() + " 个时段，首个余量 " + slots.get(0).remain();
                }
                case "queryMyAppointments" -> {
                    var orders = orderQuery.listByUser(userId, 5);
                    yield orders.isEmpty() ? "您暂无预约" :
                        "您有 " + orders.size() + " 条预约，最新状态：" + orders.get(0).statusText();
                }
                case "topServiceStaff" -> {
                    var rank = techRankQuery.top(1L, LocalDate.now(), 3);
                    yield rank.isEmpty() ? "今日暂无榜单数据" :
                        "今日热门服务者 Top" + rank.size() + "：" + rank.stream()
                            .map(r -> r.serviceStaffId() + "(" + r.deals() + "单)").toList();
                }
                default -> "未知工具：" + step.tool();
            };
        } catch (RuntimeException e) {
            log.warn("工具执行失败 tool={}", step.tool(), e);
            return "查询失败，请稍后重试";
        }
    }

    public boolean withinLimit(int rounds) { return rounds <= props.getMaxToolRounds(); }
}
