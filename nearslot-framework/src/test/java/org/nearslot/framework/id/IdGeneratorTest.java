package org.nearslot.framework.id;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** ID 生成单测（ADR-016 成熟库优先；ADR-007 雪花）。 */
class IdGeneratorTest {

    @Test
    @DisplayName("生成的 ID 单调递增")
    void monotonic() {
        long a = IdGenerator.nextId();
        long b = IdGenerator.nextId();
        assertTrue(b > a, "雪花 ID 应单调递增");
    }

    @Test
    @DisplayName("批量生成无重复（1 万次）")
    void uniqueUnderLoad() {
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertTrue(seen.add(IdGenerator.nextId()), "出现重复 ID");
        }
        assertEquals(10_000, seen.size());
    }

    @Test
    @DisplayName("nextIdStr 与 nextId 语义一致（前端精度安全）")
    void stringFormParses() {
        String s = IdGenerator.nextIdStr();
        assertNotNull(s);
        assertDoesNotThrow(() -> Long.parseLong(s));
    }

    @Test
    @DisplayName("orderId 与 traceId 必须分别取号（研究 09b 纠错点）")
    void orderAndTraceDiffer() {
        long orderId = IdGenerator.nextId();
        long traceId = IdGenerator.nextId();
        assertNotEquals(orderId, traceId, "两者不能复用同一 ID");
    }
}
