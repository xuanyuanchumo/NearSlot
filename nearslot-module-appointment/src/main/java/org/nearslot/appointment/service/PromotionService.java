package org.nearslot.appointment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.appointment.entity.TimeSlot;
import org.nearslot.appointment.mapper.TimeSlotMapper;
import org.nearslot.common.api.UserLevelQuery;
import org.nearslot.framework.cache.RedisConstants;
import org.nearslot.framework.lock.DistributedLockTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 候补提升（FR-3.2）。
 *
 * 三个关键设计：
 * 1. **异步**：取消端点只触发、不等待（research/08 §6：取消不能变慢）。
 * 2. **exactly-once**：分布式锁抢占 + 出队即删，多实例消费只提升一次。
 * 3. **等级重校验**（research/09b §1 纠错点）：候选可能是在等级变更前入队的，
 *    提升时必须**重跑**等级校验；不满足则跳过该候选、继续下一位，
 *    否则会把不合规用户塞进预约，造成"预约成功但实际无资格"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromotionService {

    private static final long TTL_SECONDS = 86400L;
    /** 单次提升最多跳过多少个不合格候选，防死循环。 */
    private static final int MAX_SKIP = 20;

    private final StringRedisTemplate redis;
    private final DistributedLockTemplate lockTemplate;
    private final TimeSlotMapper timeSlotMapper;
    private final UserLevelQuery userLevelQuery;

    /** 提升线程池：少量线程即可（提升不是高频操作，且需串行化保护）。 */
    private final ExecutorService executor = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger();
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "promotion-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    /** 异步触发提升（取消/回补后调用，不阻塞调用方）。 */
    public void promoteAsync(long slotId) {
        executor.submit(() -> {
            try {
                Long promoted = promoteNext(slotId);
                if (promoted != null) {
                    log.info("余量回流：候补已提升 slotId={} userId={}", slotId, promoted);
                }
            } catch (RuntimeException e) {
                log.error("候补提升失败 slotId={}", slotId, e);
            }
        });
    }

    /**
     * 提升队首合格候选（同步执行，供测试与内部调用）。
     * @return 被提升的 userId；无候选或全部不合格返回 null
     */
    public Long promoteNext(long slotId) {
        String lockKey = RedisConstants.LOCK + "promote:" + slotId;
        // waitMillis 必须 >0：并发提升是正常场景，应排队等待而非直接放弃（实测踩坑）
        return lockTemplate.withLock(lockKey, 3_000L, 5_000L, () -> doPromote(slotId));
    }

    private Long doPromote(long slotId) {
        String zsetKey = RedisConstants.SUBSCRIBE_ZSET + slotId;
        String setKey = RedisConstants.SUBSCRIBE_USER + slotId;
        String statusKey = RedisConstants.SUBSCRIBE_STATUS + slotId;

        TimeSlot slot = timeSlotMapper.selectById(slotId);
        if (slot == null) return null;
        int minLevel = slot.getMinLevel() == null ? 0 : slot.getMinLevel();

        for (int i = 0; i < MAX_SKIP; i++) {
            Set<String> head = redis.opsForZSet().range(zsetKey, 0, 0);
            if (head == null || head.isEmpty()) return null;
            String userId = head.iterator().next();

            // 等级重校验：不满足则跳过该候选（出队但不提升）
            if (minLevel > 0) {
                int lv = userLevelQuery.levelOf(Long.valueOf(userId));
                if (lv < minLevel) {
                    redis.opsForZSet().remove(zsetKey, userId);
                    redis.opsForSet().remove(setKey, userId);
                    redis.opsForHash().delete(statusKey, userId);
                    log.info("候补候选等级不足，已跳过 slotId={} userId={} lv={} 需={}",
                        slotId, userId, lv, minLevel);
                    continue;
                }
            }

            // 出队 + 置 SUCCESS
            redis.opsForZSet().remove(zsetKey, userId);
            redis.opsForSet().remove(setKey, userId);
            redis.opsForHash().put(statusKey, userId, String.valueOf(WaitlistService.STATUS_SUCCESS));
            redis.expire(statusKey, TTL_SECONDS, TimeUnit.SECONDS);

            log.info("候补提升成功 slotId={} userId={}", slotId, userId);
            return Long.valueOf(userId);
        }
        log.warn("候补提升达到最大跳过次数 {}，仍有不合格候选 slotId={}", MAX_SKIP, slotId);
        return null;
    }
}
