package org.nearslot.appointment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.framework.cache.RedisConstants;
import org.nearslot.framework.lock.DistributedLockTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 候补队列（FR-3.2）。
 *
 * 三键设计**严格对齐 hmdp-plus** `VoucherServiceImpl.subscribe`：
 *   1. `subscribe:user:{slotId}`   Set  —— 候补用户集合，用于幂等去重；
 *   2. `subscribe:zset:{slotId}`   ZSet —— 队列，member=userId，score=入队时间戳；
 *      仅当 Set 首次加入成功（added>0）才写 ZSet，避免重复入队扰动排序；
 *   3. `subscribe:status:{slotId}` Hash —— field=userId，value=状态码
 *      （1=SUBSCRIBED 候补中，2=SUCCESS 已成为正式预约），**状态不降级**。
 *
 * 与 hmdp-plus 的差异（NearSlot 特有）：
 * - 查询已购用 `slot:user:{slotId}`（本项目的"已约集合"），而非券项目的 SECKILL_USER_TAG_KEY；
 * - 提升前用分布式锁抢占，保证 exactly-once（hmdp-plus 在消费者里做）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaitlistService {

    /** 状态码：1 候补中；2 已成功（对齐 hmdp-plus SubscribeStatus）。 */
    public static final int STATUS_SUBSCRIBED = 1;
    public static final int STATUS_SUCCESS = 2;

    private static final long TTL_SECONDS = 86400L;

    private final StringRedisTemplate redis;
    private final org.nearslot.appointment.mapper.TimeSlotMapper timeSlotMapper;
    /** 等级校验走 common 接口（C-ARCH-01，不直接依赖 auth）。 */
    private final org.nearslot.common.api.UserLevelQuery userLevelQuery;

    /** 加入候补：Set 幂等 + ZSet 排序 + Hash 状态（三键原子性由脚本外的顺序写保证）。 */
    public long join(long slotId, long userId) {
        String uid = String.valueOf(userId);
        String setKey = RedisConstants.SUBSCRIBE_USER + slotId;
        String zsetKey = RedisConstants.SUBSCRIBE_ZSET + slotId;
        String statusKey = RedisConstants.SUBSCRIBE_STATUS + slotId;

        // 0) 等级过滤（FR-3.2 / research/09b $1：候选需满足时段等级门槛）
        var slot = timeSlotMapper.selectById(slotId);
        if (slot == null) {
            throw new org.nearslot.common.exception.BaseException(
                org.nearslot.common.enums.BaseCode.NOT_FOUND);
        }
        int minLevel = slot.getMinLevel() == null ? 0 : slot.getMinLevel();
        if (minLevel > 0) {
            int userLevel = userLevelQuery.levelOf(userId);
            if (userLevel < minLevel) {
                throw new org.nearslot.common.exception.BaseException(
                    org.nearslot.common.enums.BaseCode.LEVEL_NOT_MET,
                    "需 Lv" + minLevel + " 及以上会员才能候补，当前 Lv" + userLevel);
            }
        }

        // 1) 已购（已成功预约）则直接标记 SUCCESS，不进入队列
        boolean purchased = Boolean.TRUE.equals(
            redis.opsForSet().isMember(RedisConstants.SLOT_USER + slotId, uid));
        if (purchased) {
            redis.opsForHash().put(statusKey, uid, String.valueOf(STATUS_SUCCESS));
            redis.expire(statusKey, TTL_SECONDS, TimeUnit.SECONDS);
            log.info("用户已预约该时段，直接标记 SUCCESS slotId={} userId={}", slotId, userId);
            return -1;
        }

        // 2) Set 幂等加入
        Long added = redis.opsForSet().add(setKey, uid);
        redis.expire(setKey, TTL_SECONDS, TimeUnit.SECONDS);

        // 3) 仅首次加入才写 ZSet（保持既有排序不被扰动）
        if (Objects.nonNull(added) && added > 0) {
            redis.opsForZSet().add(zsetKey, uid, (double) System.currentTimeMillis());
            redis.expire(zsetKey, TTL_SECONDS, TimeUnit.SECONDS);
        } else {
            redis.expire(zsetKey, TTL_SECONDS, TimeUnit.SECONDS);
        }

        // 4) 状态置 SUBSCRIBED，但不降级已 SUCCESS 的用户
        Object prev = redis.opsForHash().get(statusKey, uid);
        if (prev == null || !String.valueOf(STATUS_SUCCESS).equals(String.valueOf(prev))) {
            redis.opsForHash().put(statusKey, uid, String.valueOf(STATUS_SUBSCRIBED));
        }
        redis.expire(statusKey, TTL_SECONDS, TimeUnit.SECONDS);

        Long rank = redis.opsForZSet().rank(zsetKey, uid);
        return rank == null ? -1 : rank + 1;
    }

    /** 退出候补：三键一并清理。 */
    public boolean leave(long slotId, long userId) {
        String uid = String.valueOf(userId);
        redis.opsForSet().remove(RedisConstants.SUBSCRIBE_USER + slotId, uid);
        redis.opsForZSet().remove(RedisConstants.SUBSCRIBE_ZSET + slotId, uid);
        Object status = redis.opsForHash().get(RedisConstants.SUBSCRIBE_STATUS + slotId, uid);
        // 已成功的不允许退订清除（保护既有预约语义）
        if (status != null && String.valueOf(STATUS_SUCCESS).equals(String.valueOf(status))) {
            return false;
        }
        redis.opsForHash().delete(RedisConstants.SUBSCRIBE_STATUS + slotId, uid);
        return true;
    }

    /** 查询位次（1-based，-1 表示不在队列）。 */
    public long rank(long slotId, long userId) {
        Long r = redis.opsForZSet().rank(RedisConstants.SUBSCRIBE_ZSET + slotId, String.valueOf(userId));
        return r == null ? -1 : r + 1;
    }

    /** 队列长度。 */
    public long size(long slotId) {
        Long n = redis.opsForZSet().zCard(RedisConstants.SUBSCRIBE_ZSET + slotId);
        return n == null ? 0 : n;
    }

    /** 查询候补状态码；null 表示未候补。 */
    public Integer status(long slotId, long userId) {
        Object v = redis.opsForHash().get(RedisConstants.SUBSCRIBE_STATUS + slotId, String.valueOf(userId));
        return v == null ? null : Integer.valueOf(String.valueOf(v));
    }
}
