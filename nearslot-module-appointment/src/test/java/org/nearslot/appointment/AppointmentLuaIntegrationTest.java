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
        luaService = new AppointmentLuaService(redis);
    }

    @AfterEach
    void clear() {
        var keys = redis.keys("nearslot:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    private void seedStock(long slotId, int qty) {
        redis.opsForValue().set(RedisConstants.SLOT_STOCK + slotId, String.valueOf(qty));
    }

    @Test
    @DisplayName("并发 100 抢 1 余量：恰好 1 成功、99 库存不足")
    void concurrentBook_onlyOneSucceeds() throws Exception {
        long slotId = 1001L, techId = 2001L;
        seedStock(slotId, 1);
        long start = 1_700_000_000_000L, end = start + 3_600_000L;

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
                BookResult r = luaService.book(uid, slotId, techId, start, end, uid * 10, uid * 10 + 1);
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
        long s = 1_700_000_000_000L, e = s + 3_600_000L;

        assertTrue(luaService.book(uid, slotId, techId, s, e, 1, 2).success());
        BookResult second = luaService.book(uid, slotId, techId, s, e, 3, 4);

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
        long base = 1_700_000_000_000L;

        assertTrue(luaService.book(10L, slotA, techId, base, base + 3_600_000L, 1, 2).success());

        long slotB = 1004L;
        seedStock(slotB, 5);
        BookResult overlap = luaService.book(20L, slotB, techId,
            base + 1_800_000L, base + 5_400_000L, 3, 4);

        assertFalse(overlap.success(), "技师同一时刻不得被两单占用");
        assertEquals(BaseCode.SLOT_CLOSED.getCode(), overlap.getCode());
    }

    @Test
    @DisplayName("回补反转语义（ADR-005）：回补后余量恢复、库存键删除防漂移")
    void rollback_reversesSemantics() {
        long slotId = 1005L, techId = 2005L, uid = 60_001L;
        seedStock(slotId, 3);
        long s = 1_700_000_000_000L, e = s + 3_600_000L;

        BookResult book = luaService.book(uid, slotId, techId, s, e, 100, 101);
        assertTrue(book.success());
        assertEquals(2, book.getAfterQty());

        BookResult rb = luaService.rollback(uid, slotId, techId, s, e, 100, 102, 1);
        assertTrue(rb.success());
        assertEquals(book.getAfterQty() + 1, rb.getBeforeQty(), "回补 beforeQty 应为回补前余量+1（反转语义）");
        assertEquals(book.getAfterQty(), rb.getAfterQty(), "回补 afterQty 应为回补前余量");
        assertNull(redis.opsForValue().get(RedisConstants.SLOT_STOCK + slotId),
            "回补应删除库存键等待重载，避免值漂移");
        assertFalse(Boolean.TRUE.equals(
            redis.opsForSet().isMember(RedisConstants.SLOT_USER + slotId, String.valueOf(uid))),
            "回补应移除用户一人一时段标记");
    }

    @Test
    @DisplayName("trace 审计日志：抢约写入 Hash，含前后余量与 traceId")
    void traceLogWritten() {
        long slotId = 1006L, techId = 2006L, uid = 70_001L;
        seedStock(slotId, 2);
        long s = 1_700_000_000_000L, e = s + 3_600_000L;

        luaService.book(uid, slotId, techId, s, e, 200, 999);

        var entries = redis.opsForHash().entries(RedisConstants.SLOT_TRACE + slotId);
        assertEquals(1, entries.size(), "应对 traceId=999 写一条审计日志");
        String log = String.valueOf(entries.get("999"));
        assertTrue(log.contains("beforeQty"));
        assertTrue(log.contains("afterQty"));
        assertTrue(log.contains("logType"));
        assertTrue(log.contains("999"));
    }
}
