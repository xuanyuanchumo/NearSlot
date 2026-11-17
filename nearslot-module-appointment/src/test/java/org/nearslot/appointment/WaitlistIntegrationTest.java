package org.nearslot.appointment;

import org.junit.jupiter.api.*;
import org.nearslot.appointment.entity.TimeSlot;
import org.nearslot.appointment.mapper.AppointmentOrderMapper;
import org.nearslot.appointment.mapper.TimeSlotMapper;
import org.nearslot.appointment.service.HoldTimeoutTask;
import org.nearslot.appointment.service.SlotBookingCore;
import org.nearslot.appointment.service.PromotionService;
import org.nearslot.appointment.service.WaitlistService;
import org.nearslot.common.api.UserLevelQuery;
import org.nearslot.framework.cache.RedisConstants;
import org.nearslot.framework.lock.DistributedLockTemplate;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 候补队列真实 Redis 集成测试（FR-3.2）。
 * 验证：公平排序、幂等入队、位次查询、exactly-once 提升。
 */
@Tag("integration")
class WaitlistIntegrationTest {

    static StringRedisTemplate redis;
    static WaitlistService waitlist;
    static PromotionService promotion;
    static RedissonClient redisson;

    @BeforeAll
    static void setUp() {
        String host = System.getProperty("nearslot.test.redis.host", "");
        org.junit.jupiter.api.Assumptions.assumeTrue(!host.isBlank(), "未配置 Redis，跳过");
        int port = Integer.parseInt(System.getProperty("nearslot.test.redis.port", "6379"));
        String pwd = System.getProperty("nearslot.test.redis.password", "");

        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(host, port);
        if (!pwd.isBlank()) cfg.setPassword(pwd);
        LettuceConnectionFactory f = new LettuceConnectionFactory(cfg);
        f.afterPropertiesSet();
        redis = new StringRedisTemplate(f);
        redis.afterPropertiesSet();

        Config rc = new Config();
        var single = rc.useSingleServer().setAddress("redis://" + host + ":" + port);
        if (!pwd.isBlank()) single.setPassword(pwd);
        redisson = Redisson.create(rc);

        TimeSlotMapper slotMapper = org.mockito.Mockito.mock(TimeSlotMapper.class);
        TimeSlot slot = new TimeSlot();
        slot.setId(1L);
        slot.setMinLevel(0);
        org.mockito.Mockito.when(slotMapper.selectById(org.mockito.ArgumentMatchers.anyLong()))
            .thenReturn(slot);

        UserLevelQuery levelQuery = uid -> 10;   // 集成测试默认满级，聚焦队列语义

        waitlist = new WaitlistService(redis, slotMapper, levelQuery);
        // 提升会被真实调用（不再只改 Redis）：给出可工作的插桩，避免集成测试因缺 Bean 失败
        SlotBookingCore bookingCore = org.mockito.Mockito.mock(SlotBookingCore.class);
        org.mockito.Mockito.when(bookingCore.book(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong()))
            .thenAnswer(inv -> 9_000_000L + System.nanoTime() % 100_000L);
        promotion = new PromotionService(redis, new DistributedLockTemplate(redisson), slotMapper,
            levelQuery, bookingCore);
    }

    @AfterAll
    static void tearDown() {
        if (redisson != null) redisson.shutdown();
    }

    @AfterEach
    void clear() {
        var keys = redis.keys("nearslot:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @Test
    @DisplayName("入队按时间先后排序，位次递增")
    void joinAssignsIncreasingRank() throws Exception {
        long slotId = 8001L;
        long r1 = waitlist.join(slotId, 101L);
        Thread.sleep(5);
        long r2 = waitlist.join(slotId, 102L);
        Thread.sleep(5);
        long r3 = waitlist.join(slotId, 103L);

        assertEquals(1, r1);
        assertEquals(2, r2);
        assertEquals(3, r3);
        assertEquals(3, waitlist.size(slotId));
    }

    @Test
    @DisplayName("幂等入队：重复 join 返回原序位，不重复占位")
    void joinIsIdempotent() {
        long slotId = 8002L;
        long first = waitlist.join(slotId, 201L);
        long again = waitlist.join(slotId, 201L);

        assertEquals(first, again, "重复加入应返回原序位");
        assertEquals(1, waitlist.size(slotId), "队列长度不应增长");
    }

    @Test
    @DisplayName("位次查询与退出")
    void rankAndLeave() {
        long slotId = 8003L;
        waitlist.join(slotId, 301L);
        waitlist.join(slotId, 302L);

        assertEquals(2, waitlist.rank(slotId, 302L));
        assertTrue(waitlist.leave(slotId, 301L));
        assertEquals(1, waitlist.rank(slotId, 302L), "前面的人退出后位次应前移");
        assertEquals(-1, waitlist.rank(slotId, 301L), "已退出者查询应返回 -1");
    }

    @Test
    @DisplayName("提升队首：FIFO 出队，且队列缩短（exactly-once）")
    void promoteNextIsFifoAndRemoves() {
        long slotId = 8004L;
        waitlist.join(slotId, 401L);
        waitlist.join(slotId, 402L);

        Long first = promotion.promoteNext(slotId);
        assertEquals(401L, first, "应先提升最早入队者");
        assertEquals(1, waitlist.size(slotId), "提升后应出队");

        Long second = promotion.promoteNext(slotId);
        assertEquals(402L, second);

        assertNull(promotion.promoteNext(slotId), "空队列应返回 null");
    }

    @Test
    @DisplayName("并发提升同一 slot：分布式锁保证不重复提升同一人")
    void concurrentPromoteDoesNotDuplicate() throws Exception {
        long slotId = 8005L;
        for (long uid = 501; uid <= 510; uid++) waitlist.join(slotId, uid);

        java.util.Set<Long> promoted = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(5);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < 5; i++) {
            futures.add(pool.submit(() -> {
                try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                Long u = promotion.promoteNext(slotId);
                if (u != null) promoted.add(u);
            }));
        }
        go.countDown();
        for (var f : futures) f.get(20, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(5, promoted.size(), "5 次提升应各得不同用户，无重复");
        assertEquals(5, waitlist.size(slotId), "原始 10 人，提升 5 人后应剩 5 人");
    }
}
