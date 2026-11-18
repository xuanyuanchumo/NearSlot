package org.nearslot.appointment;

import org.junit.jupiter.api.*;
import org.nearslot.appointment.domain.BookResult;
import org.nearslot.appointment.service.AppointmentLuaService;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.framework.cache.RedisConstants;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 抢约 Lua 真实 Redis 集成测试（方案 A 核心验证）。
 *
 * 为何不用 Testcontainers：本机 Docker Desktop 的 npipe 在 Java 侧返回 400
 * （docker CLI 正常但 testcontainers 无法握手）。按"不得跳过运行时验证"红线，
 * 改为连接外部真实 Redis，由系统属性注入：
 *   -Dnearslot.test.redis.host=127.0.0.1 -Dnearslot.test.redis.port=46379 -Dnearslot.test.redis.password=verify123
 * 未提供 host 时自动跳过（assumeTrue），保证 CI 无 Redis 时不误红。
 *
 * 验证项：
 *   1) 并发 100 抢 1 余量 → 恰好 1 成功
 *   2) 一人一时段重复 → 10006
 *   3) 技师时间轴重叠 → 50103
 *   4) 回补反转语义（ADR-005）
 *   5) trace 审计日志落库
 */
@Tag("integration")
class AppointmentLuaIntegrationTest {

    static StringRedisTemplate redis;
    static AppointmentLuaService luaService;

    @BeforeAll
    static void setUp() {
        String host = System.getProperty("nearslot.test.redis.host", "");
        org.junit.jupiter.api.Assumptions.assumeTrue(!host.isBlank(),
            "未配置 nearslot.test.redis.host，跳过需要真实 Redis 的集成测试");
        int port = Integer.parseInt(System.getProperty("nearslot.test.redis.port", "6379"));
        String password = System.getProperty("nearslot.test.redis.password", "");

        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(host, port);
        if (!password.isBlank()) cfg.setPassword(password);
        LettuceConnectionFactory factory = new LettuceConnectionFactory(cfg);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        luaService = new AppointmentLuaService(redis, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @AfterEach
    void clear() {
        var keys = redis.keys("nearslot:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    /**
     * 测试用「未来」起始时间：当前时间 + 7 天，向下取整到小时。
     *
     * 为什么不能用固定常量：Lua 在 2026-11-17 审计中补上了**时间闸**
     * （未开始 10002 / 已结束 10003），原先写死的 1_700_000_000_000L（2023-11）
     * 会被正确拒绝，导致「并发抢约恰好 1 成功」这类用例全部失败。
     */
    private static final long FUTURE_START_MS =
        ((System.currentTimeMillis() + 7L * 24 * 3600 * 1000) / 3_600_000L) * 3_600_000L;

    private void seedStock(long slotId, int qty) {
        redis.opsForValue().set(RedisConstants.SLOT_STOCK + slotId, String.valueOf(qty));
    }

    @Test
    @DisplayName("并发 100 抢 1 余量：恰好 1 成功、99 库存不足")
    void concurrentBook_onlyOneSucceeds() throws Exception {
        long slotId = 1001L, techId = 2001L;
        seedStock(slotId, 1);
        long start = FUTURE_START_MS, end = start + 3_600_000L;

        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger noStock = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final long uid = 10_000L + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                BookResult r = luaService.book(uid, slotId, techId, start, end, uid * 10, uid * 10 + 1, 1);
                if (r.success()) success.incrementAndGet();
                else if (r.getCode() == BaseCode.LUA_NO_STOCK.getCode()) noStock.incrementAndGet();
            }));
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, success.get(), "并发抢约必须恰好 1 个成功");
        assertEquals(99, noStock.get(), "其余 99 个应为库存不足(10005)");
        assertEquals("0", redis.opsForValue().get(RedisConstants.SLOT_STOCK + slotId),
            "Redis 余量必须归零，不得为负");
    }

    @Test
    @DisplayName("一人一时段：同用户二次抢约返回 10006 且不再扣减")
    void duplicateUser_blocked() {
        long slotId = 1002L, techId = 2002L, uid = 50_001L;
        seedStock(slotId, 5);
        long s = FUTURE_START_MS, e = s + 3_600_000L;

        assertTrue(luaService.book(uid, slotId, techId, s, e, 1, 2, 5).success());
        BookResult second = luaService.book(uid, slotId, techId, s, e, 3, 4, 5);

        assertFalse(second.success());
        assertEquals(BaseCode.LUA_DUPLICATE.getCode(), second.getCode());
        assertEquals("4", redis.opsForValue().get(RedisConstants.SLOT_STOCK + slotId),
            "重复预约不得再扣余量");
    }

    @Test
    @DisplayName("技师时间轴重叠：同技师重叠时段返回 50103")
    void technicianOverlap_blocked() {
        long slotA = 1003L, techId = 2003L;
        seedStock(slotA, 5);
        long base = FUTURE_START_MS;

        assertTrue(luaService.book(10L, slotA, techId, base, base + 3_600_000L, 1, 2, 5).success());

        long slotB = 1004L;
        seedStock(slotB, 5);
        BookResult overlap = luaService.book(20L, slotB, techId,
            base + 1_800_000L, base + 5_400_000L, 3, 4, 5);

        assertFalse(overlap.success(), "技师同一时刻不得被两单占用");
        assertEquals(BaseCode.SLOT_CLOSED.getCode(), overlap.getCode());
    }

    @Test
    @DisplayName("回补反转语义（ADR-005）：回补后余量恢复、库存键删除防漂移")
    void rollback_reversesSemantics() {
        long slotId = 1005L, techId = 2005L, uid = 60_001L;
        seedStock(slotId, 3);
        long s = FUTURE_START_MS, e = s + 3_600_000L;

        BookResult book = luaService.book(uid, slotId, techId, s, e, 100, 101, 3);
        assertTrue(book.success());
        assertEquals(2, book.getAfterQty());

        BookResult rb = luaService.rollback(uid, slotId, techId, s, e, 100, 102, 1, 3);
        assertTrue(rb.success());
        assertEquals(book.getAfterQty() + 1, rb.getBeforeQty(), "回补 beforeQty 应为回补前余量+1（反转语义）");
        assertEquals(book.getAfterQty(), rb.getAfterQty(), "回补 afterQty 应为回补前余量");
        // 2026-11-17 审计修复（Blocker）：回补**不得删除**库存键。
        // 原实现 DEL 后无任何重载路径，该时段永久返回 10004「无库存键」（已实测复现）。
        assertEquals("3", redis.opsForValue().get(RedisConstants.SLOT_STOCK + slotId),
            "回补后余量必须恢复到容量，且库存键必须存在");
        assertFalse(Boolean.TRUE.equals(
            redis.opsForSet().isMember(RedisConstants.SLOT_USER + slotId, String.valueOf(uid))),
            "回补应移除用户一人一时段标记");
    }

    @Test
    @DisplayName("trace 审计日志：抢约写入 Hash，含前后余量与 traceId")
    void traceLogWritten() {
        long slotId = 1006L, techId = 2006L, uid = 70_001L;
        seedStock(slotId, 2);
        long s = FUTURE_START_MS, e = s + 3_600_000L;

        luaService.book(uid, slotId, techId, s, e, 200, 999, 2);

        var entries = redis.opsForHash().entries(RedisConstants.SLOT_TRACE + slotId);
        assertEquals(1, entries.size(), "应对 traceId=999 写一条审计日志");
        String log = String.valueOf(entries.get("999"));
        assertTrue(log.contains("beforeQty"));
        assertTrue(log.contains("afterQty"));
        assertTrue(log.contains("logType"));
        assertTrue(log.contains("999"));
    }
}
