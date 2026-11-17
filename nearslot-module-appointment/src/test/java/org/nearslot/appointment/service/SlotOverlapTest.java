package org.nearslot.appointment.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技师时间轴重叠判定（research/08 §3 特色约束）的纯逻辑镜像。
 * 等价于 Lua: ZRANGEBYSCORE '-inf' startTs 后判断 endsAt > startTs。
 */
class SlotOverlapTest {
    /** 判定新时段 [ns,ne) 是否与已有 [es,ee) 冲突。 */
    private static boolean overlaps(long es, long ee, long ns, long ne) {
        return ns < ee && ne > es;
    }

    @Test
    void adjacentSlotsDoNotConflict() {
        // 已有 [10,11)，新 [11,12) 边界相接，不算重叠
        assertFalse(overlaps(10L, 11L, 11L, 12L));
    }

    @Test
    void containedSlotConflicts() {
        // 已有 [10,12)，新 [10,11) 被包含
        assertTrue(overlaps(10L, 12L, 10L, 11L));
    }

    @Test
    void sameStartConflicts() {
        assertTrue(overlaps(10L, 11L, 10L, 12L));
    }

    @Test
    void disjointLaterSlotsDoNotConflict() {
        assertFalse(overlaps(10L, 11L, 13L, 14L));
    }

    @Test
    void disjointEarlierSlotsDoNotConflict() {
        assertFalse(overlaps(10L, 11L, 8L, 9L));
    }
}
