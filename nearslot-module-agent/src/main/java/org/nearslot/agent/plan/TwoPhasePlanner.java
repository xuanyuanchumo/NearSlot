package org.nearslot.agent.plan;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 两阶段规划器（FR-5.1 / ADR-023）。
 *
 * 阶段一 PLAN：只产出"要做什么"的步骤清单，不执行任何工具。
 * 阶段二 EXECUTE：逐条执行，每步产出 progress 事件。
 *
 * 为什么要分两阶段：
 *   1. 让用户在执行前看到计划（可中止），避免模型"边想边做"造成不可控副作用；
 *   2. 计划是纯数据结构，可持久化、可回放、可测试；
 *   3. 与双闸口配合——BLOCK 类在 PLAN 前就被拦，CONFIRM 类在 EXECUTE 前索要令牌。
 *
 * 约束：工具调用总轮次 ≤ 5（research/04 实证值），超出直接失败而非无限循环。
 */
@Slf4j
@Component
public class TwoPhasePlanner {

    /** 单次对话允许的最大工具执行轮次。 */
    public static final int MAX_TOOL_ROUNDS = 5;

    /** 计划中的一步。 */
    public record PlanStep(int index, String tool, String purpose) {}

    /** 完整计划。 */
    public record Plan(List<PlanStep> steps) {
        public boolean isEmpty() { return steps == null || steps.isEmpty(); }
    }

    /**
     * 构建计划（PLAN 阶段，纯数据，不执行）。
     *
     * 当前为骨架实现：按意图关键词选择工具序列。
     * Phase 3 正式版应替换为 LLM 规划（Spring AI ChatClient + 结构化输出）。
     */
    public Plan plan(String userInput) {
        List<PlanStep> steps = new ArrayList<>();
        if (userInput == null || userInput.isBlank()) return new Plan(steps);

        boolean asksShop = userInput.contains("服务商") || userInput.contains("店铺") || userInput.contains("附近");
        boolean asksSlot = userInput.contains("时段") || userInput.contains("时间") || userInput.contains("预约");

        int i = 1;
        if (asksShop) steps.add(new PlanStep(i++, "queryShops", "查询符合条件的服务商"));
        if (asksSlot) steps.add(new PlanStep(i++, "querySlots", "查询可预约时段"));
        if (steps.size() > MAX_TOOL_ROUNDS) {
            steps = steps.subList(0, MAX_TOOL_ROUNDS);
        }
        log.debug("生成计划 steps={}", steps.size());
        return new Plan(steps);
    }

    /** 校验轮次是否超限。 */
    public boolean withinLimit(int rounds) {
        return rounds <= MAX_TOOL_ROUNDS;
    }
}
