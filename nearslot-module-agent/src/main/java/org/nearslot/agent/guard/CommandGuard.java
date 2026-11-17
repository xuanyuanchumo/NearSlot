package org.nearslot.agent.guard;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 指令闸口（FR-5.1 / 双闸口之一）——BLOCK 类操作直接拒绝。
 *
 * 依据 research/04 §双闸口 + FR-5.2 验收标准：
 *   "删除类指令被 BLOCK 并给出理由；确认类指令进入 confirm 流程，TTL 300s"。
 *
 * 为什么必须前置拦截：LLM 可能生成危险指令（删库/批量退款/清空缓存），
 * 一旦进入工具执行阶段就来不及了。此闸口在**规划前**做纯文本判定，
 * 不依赖模型自省，是可测试的硬规则。
 */
@Component
public class CommandGuard {

    /** 明确禁止的动作关键词（删除/清空/退款/重置）。 */
    private static final List<String> BLOCK_KEYWORDS = List.of(
        "删除", "清空", "删库", "drop", "truncate", "delete all",
        "批量退款", "refund all", "重置所有", "reset all"
    );

    /** 需二次确认的动作关键词（取消/改期/退款单笔）。 */
    private static final List<String> CONFIRM_KEYWORDS = List.of(
        "取消", "退订", "改期", "退款", "cancel", "refund", "reschedule"
    );

    public enum Decision { ALLOW, CONFIRM, BLOCK }

    /**
     * 判定用户输入。
     * @return ALLOW 直接执行 / CONFIRM 需二次确认 / BLOCK 直接拒绝
     */
    public Decision decide(String userInput) {
        if (userInput == null || userInput.isBlank()) return Decision.ALLOW;
        String s = userInput.toLowerCase(Locale.ROOT);
        for (String kw : BLOCK_KEYWORDS) {
            if (s.contains(kw.toLowerCase(Locale.ROOT))) return Decision.BLOCK;
        }
        for (String kw : CONFIRM_KEYWORDS) {
            if (s.contains(kw.toLowerCase(Locale.ROOT))) return Decision.CONFIRM;
        }
        return Decision.ALLOW;
    }

    /** BLOCK 时给用户的理由（不泄露内部实现）。 */
    public String blockReason() {
        return "该操作涉及数据删除或批量变更，系统不允许通过对话执行，请前往管理端操作";
    }
}
