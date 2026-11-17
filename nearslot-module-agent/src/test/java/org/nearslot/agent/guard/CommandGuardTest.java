package org.nearslot.agent.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 指令闸口单测（FR-5.1 / FR-5.2 验收标准：删除类被 BLOCK）。 */
class CommandGuardTest {

    private final CommandGuard guard = new CommandGuard();

    @Test
    @DisplayName("删除类指令 -> BLOCK，并给出理由")
    void deleteCommandsAreBlocked() {
        assertEquals(CommandGuard.Decision.BLOCK, guard.decide("帮我删除所有订单"));
        assertEquals(CommandGuard.Decision.BLOCK, guard.decide("drop table orders"));
        assertEquals(CommandGuard.Decision.BLOCK, guard.decide("清空缓存"));
        assertEquals(CommandGuard.Decision.BLOCK, guard.decide("批量退款"));
        assertFalse(guard.blockReason().isBlank());
    }

    @Test
    @DisplayName("取消/改期类 -> CONFIRM 二次确认")
    void cancelNeedsConfirm() {
        assertEquals(CommandGuard.Decision.CONFIRM, guard.decide("我要取消预约 3001"));
        assertEquals(CommandGuard.Decision.CONFIRM, guard.decide("帮我把明天的预约改期"));
        assertEquals(CommandGuard.Decision.CONFIRM, guard.decide("refund this order"));
    }

    @Test
    @DisplayName("查询类 -> ALLOW 直接执行")
    void queryIsAllowed() {
        assertEquals(CommandGuard.Decision.ALLOW, guard.decide("我想预约明天下午的推拿"));
        assertEquals(CommandGuard.Decision.ALLOW, guard.decide("附近有哪些服务商"));
        assertEquals(CommandGuard.Decision.ALLOW, guard.decide(""));
        assertEquals(CommandGuard.Decision.ALLOW, guard.decide(null));
    }

    @Test
    @DisplayName("BLOCK 优先于 CONFIRM（同时含两类关键词时更严格）")
    void blockTakesPrecedence() {
        assertEquals(CommandGuard.Decision.BLOCK, guard.decide("帮我批量退款并删除记录"));
    }
}
