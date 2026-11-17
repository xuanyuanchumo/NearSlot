package org.nearslot.appointment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.framework.cache.RedisConstants;
import org.nearslot.framework.lock.DistributedLockTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 候补提升（FR-3.2 / research/08 §5）。
 *
 * 从 WaitlistService 拆出：提升是**异步**动作（取消/回补后触发），与入队解耦。
 * exactly-once：分布式锁抢占（同一 slot 同时只有一个提升者）+ 出队即删。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromotionService {

    private static final long TTL_SECONDS = 86400L;

    private final StringRedisTemplate redis;
    private final DistributedLockTemplate lockTemplate;

    /**
     * 提升队首候补者：FIFO 出队 + 状态置 SUCCESS。
     * @return 被提升的 userId；无候选返回 null。
     */
    public Long promoteNext(long slotId) {
        String lockKey = RedisConstants.LOCK + "promote:" + slotId;
        // waitMillis 必须 >0：并发提升是正常场景，应排队等待而非直接放弃（实测踩坑）
        return lockTemplate.withLock(lockKey, 3_000L, 5_000L, () -> {
            String zsetKey = RedisConstants.SUBSCRIBE_ZSET + slotId;
            Set<String> head = redis.opsForZSet().range(zsetKey, 0, 0);
            if (head == null || head.isEmpty()) return null;
            String userId = head.iterator().next();

            // 出队：ZSet + Set 同步清理
            redis.opsForZSet().remove(zsetKey, userId);
            redis.opsForSet().remove(RedisConstants.SUBSCRIBE_USER + slotId, userId);
            // 状态置 SUCCESS（不降级，供前端查询）
            String statusKey = RedisConstants.SUBSCRIBE_STATUS + slotId;
            redis.opsForHash().put(statusKey, userId, String.valueOf(WaitlistService.STATUS_SUCCESS));
            redis.expire(statusKey, TTL_SECONDS, TimeUnit.SECONDS);

            log.info("候补提升 slotId={} userId={}", slotId, userId);
            return Long.valueOf(userId);
        });
    }
}
