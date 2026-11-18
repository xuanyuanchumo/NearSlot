package org.nearslot.appointment.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nearslot.appointment.domain.BookResult;
import org.nearslot.framework.cache.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 抢约 Lua 执行器单测（参数契约 + 返回解析）。
 *
 * 与 AppointmentLuaIntegrationTest 的分工：
 * 集成测试验证**脚本语义**（真实 Redis 跑三检查），本测试验证**Java 侧契约**——
 * KEYS/ARGV 的顺序与数量、capacity/nowTs 是否真的传下去、返回 JSON 如何映射为 BookResult。
 * 两者缺一不可：ARGV 错位在集成测试里表现为"意外拒绝"，很难定位；
 * 而这里能直接断言第 9/10 位参数。
 */
class AppointmentLuaServiceTest {

    private static final long SLOT_ID = 2001L;
    private static final long TECH_ID = 4001L;
    private static final long USER_ID = 3001L;

    private StringRedisTemplate redis;
    private AppointmentLuaService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        service = new AppointmentLuaService(redis, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    private List<Object> captureArgs(String luaResult) {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenReturn(luaResult);
        return null;
    }

    @Test
    @DisplayName("book: KEYS 顺序为 stock/user/trace/timeline")
    @SuppressWarnings("unchecked")
    void bookPassesKeysInOrder() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenReturn("{\"code\":200,\"beforeQty\":3,\"deductQty\":1,\"afterQty\":2}");

        service.book(USER_ID, SLOT_ID, TECH_ID, 1_000L, 2_000L, 1L, 2L, 5);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
        assertEquals(List.of(
            RedisConstants.SLOT_STOCK + SLOT_ID,
            RedisConstants.SLOT_USER + SLOT_ID,
            RedisConstants.SLOT_TRACE + SLOT_ID,
            RedisConstants.PREFIX + "staff:timeline:" + TECH_ID), keys.getValue());
    }

    @Test
    @DisplayName("book: ARGV[9]=capacity, ARGV[10]=nowTs（余量键缺失兜底 + 时间闸都依赖它们）")
    @SuppressWarnings("unchecked")
    void bookPassesCapacityAndNow() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenReturn("{\"code\":200}");

        long before = System.currentTimeMillis();
        service.book(USER_ID, SLOT_ID, TECH_ID, 1_000L, 2_000L, 1L, 2L, 7);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(RedisScript.class), anyList(), args.capture());
        Object[] actual = args.getValue();
        assertEquals(10, actual.length, "book 脚本需要 10 个 ARGV");
        assertEquals("7", actual[8], "ARGV[9] 必须是 capacity");
        long nowTs = Long.parseLong(String.valueOf(actual[9]));
        assertTrue(nowTs >= before, "ARGV[10] 必须是服务端当前毫秒时间戳");
    }

    @Test
    @DisplayName("book: capacity 为 0 时下限钳到 1（避免 SET 0 让时段永久不可约）")
    @SuppressWarnings("unchecked")
    void bookClampsCapacity() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenReturn("{\"code\":200}");

        service.book(USER_ID, SLOT_ID, TECH_ID, 1_000L, 2_000L, 1L, 2L, 0);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(RedisScript.class), anyList(), args.capture());
        assertEquals("1", args.getValue()[8]);
    }

    @Test
    @DisplayName("rollback: ARGV[10]=capacity（INCR 后封顶，防重复回补超卖）")
    @SuppressWarnings("unchecked")
    void rollbackPassesCapacity() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenReturn("{\"code\":200}");

        service.rollback(USER_ID, SLOT_ID, TECH_ID, 1_000L, 2_000L, 1L, 2L, 1, 4);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(RedisScript.class), anyList(), args.capture());
        // 11 个 ARGV：末位 nowTs 供脚本计算审计时间戳
        assertEquals(11, args.getValue().length, "rollback 脚本需要 11 个 ARGV");
        assertEquals("4", args.getValue()[9], "ARGV[10] 必须是 capacity");
        // 关键：nowTs 必须由服务端传入。脚本内若自己调 TIME，会违反
        // 「非确定性命令之后不得写数据」而被 Redis 拒绝（实测直接 500）。
        long nowTs = Long.parseLong(String.valueOf(args.getValue()[10]));
        assertTrue(nowTs > 0, "ARGV[11] 必须是服务端当前毫秒时间戳");
    }

    @Test
    @DisplayName("解析: changeQty 别名映射到 deductQty（历史契约兼容）")
    @SuppressWarnings("unchecked")
    void parsesChangeQtyAlias() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenReturn("{\"code\":200,\"beforeQty\":3,\"changeQty\":1,\"afterQty\":2}");

        BookResult r = service.book(USER_ID, SLOT_ID, TECH_ID, 1_000L, 2_000L, 1L, 2L, 3);

        assertTrue(r.success());
        assertEquals(3, r.getBeforeQty());
        assertEquals(1, r.getDeductQty());
        assertEquals(2, r.getAfterQty());
    }

    @Test
    @DisplayName("解析: 非法 JSON -> 50001 兜底，不抛异常打断链路")
    @SuppressWarnings("unchecked")
    void parsesInvalidJsonAsInternalError() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenReturn("not-a-json");

        BookResult r = service.book(USER_ID, SLOT_ID, TECH_ID, 1_000L, 2_000L, 1L, 2L, 3);

        assertEquals(50001, r.getCode());
        assertFalse(r.success());
    }

    @Test
    @DisplayName("解析: null 结果 -> 50001 兜底")
    @SuppressWarnings("unchecked")
    void parsesNullAsInternalError() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenReturn(null);

        assertEquals(50001, service.book(USER_ID, SLOT_ID, TECH_ID, 1_000L, 2_000L, 1L, 2L, 3)
            .getCode());
    }

    @Test
    @DisplayName("解析: 未知字段被忽略（Lua 演进不应炸链路）")
    @SuppressWarnings("unchecked")
    void ignoresUnknownFields() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
            .thenReturn("{\"code\":200,\"beforeQty\":2,\"afterQty\":1,\"deductQty\":1,\"newField\":\"x\"}");

        assertTrue(service.book(USER_ID, SLOT_ID, TECH_ID, 1_000L, 2_000L, 1L, 2L, 2).success());
    }
}
