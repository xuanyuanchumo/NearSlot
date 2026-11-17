package org.nearslot.appointment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.framework.cache.RedisConstants;
import org.nearslot.framework.lock.DistributedLockTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 候补队列（FR-3.2 / research/08 §5）。
 *
 * 设计要点（区别于 hmdp-plus 同步 autoIssue）：
 * 1. **公平排序**：score = tsMs*1000 + jitter（同毫秒随机抖动，防网络邻近者插队）。
 * 2. **幂等入队**：Lua 判断已存在则返回原序位，不重复写入。
 * 3. **取消端点不做提升**：只发 slot.opened 事件，提升走异步消费（避免取消变慢）。
 * 4. **exactly-once**：提升前用分布式锁抢占 + offer 唯一约束兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaitlistService {

    private static final String JOIN_LUA =
        "if redis.call('ZSCORE', KEYS[1], ARGV[1]) then "
        + "  return redis.call('ZRANK', KEYS[1], ARGV[1]) + 1 "
        + "end "
        + "redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1]) "
        + "return redis.call('ZRANK', KEYS[1], ARGV[1]) + 1";

    private final StringRedisTemplate redis;
    private final DistributedLockTemplate lockTemplate;

    /** 加入候补，返回位次（1-based）；已存在则返回原序位。 */
    public long join(long slotId, long userId) {
        long ts = System.currentTimeMillis();
        long jitter = (long) (Math.random() * 999);
        double score = ts * 1000.0 + jitter;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(JOIN_LUA);
        script.setResultType(Long.class);
        Long rank = redis.execute(script,
            List.of(RedisConstants.CANDIDATE + slotId), String.valueOf(userId), String.valueOf(score));
        return rank == null ? -1 : rank;
    }

    /** 退出候补。 */
    public boolean leave(long slotId, long userId) {
        Long removed = redis.opsForZSet().remove(RedisConstants.CANDIDATE + slotId, String.valueOf(userId));
        return removed != null && removed > 0;
    }

    /** 查询位次与队列长度（前端展示「你前面还有 N 人」）。 */
    public long rank(long slotId, long userId) {
        Long r = redis.opsForZSet().rank(RedisConstants.CANDIDATE + slotId, String.valueOf(userId));
        return r == null ? -1 : r + 1;
    }

    public long size(long slotId) {
        Long n = redis.opsForZSet().zCard(RedisConstants.CANDIDATE + slotId);
        return n == null ? 0 : n;
    }

    /**
     * 提升队首（由 slot.opened 事件异步调用）。
     *
     * exactly-once 保障：分布式锁抢占（同 slot 同一时刻只有一个提升者）。
     * 返回被提升的 userId，无候选返回 null。
     */
    public Long promoteNext(long slotId) {
        String lockKey = RedisConstants.LOCK + "promote:" + slotId;
        // waitMillis 必须 >0：并发提升是正常场景，应排队等待而非直接放弃。
        // 实测踩坑：传 0 时 5 个并发线程只有 1 个拿到锁，其余静默返回 null，
        // 表现为「候补只提升 1 人」，队列中真实用户被饿死。
        return lockTemplate.withLock(lockKey, 3_000L, 5_000L, () -> {
            Set<String> head = redis.opsForZSet().range(RedisConstants.CANDIDATE + slotId, 0, 0);
            if (head == null || head.isEmpty()) return null;
            String userId = head.iterator().next();
            // 出队（避免重复提升）
            redis.opsForZSet().remove(RedisConstants.CANDIDATE + slotId, userId);
            log.info("候补提升 slotId={} userId={}", slotId, userId);
            return Long.valueOf(userId);
        });
    }
}
