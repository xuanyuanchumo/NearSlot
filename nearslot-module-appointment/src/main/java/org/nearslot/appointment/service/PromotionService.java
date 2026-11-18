package org.nearslot.appointment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.appointment.entity.TimeSlot;
import org.nearslot.appointment.event.SlotOpenedEvent;
import org.nearslot.appointment.mapper.TimeSlotMapper;
import org.nearslot.common.api.UserLevelQuery;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.framework.cache.RedisConstants;
import org.nearslot.framework.lock.DistributedLockTemplate;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 候补提升（FR-3.2）。
 *
 * 三个关键设计：
 * 1. **异步**：监听 {@link SlotOpenedEvent}，在独立线程执行，取消端点只做标记+发事件
 *    （research/08 §5：同步提升让取消变慢，且失败重试耦合在关键路径）。
 * 2. **exactly-once**：分布式锁抢占 + 出队即删，多实例消费只提升一次。
 * 3. **等级重校验**（research/09b §1 纠错点）：候选可能是在等级变更前入队的，
 *    提升时必须**重跑**等级校验；不满足则跳过该候选、继续下一位。
 *
 * **2026-11-17 审计修复（Blocker）**：
 * 此前提升只改 Redis 三键、把用户标成 SUCCESS，**既不创建订单也不扣库存**——
 * 用户在「我的预约」里看不到任何东西，余量也没被真正占用（下一个抢约者照样成功），
 * 等于候补功能完全没有闭环。
 * 现改为：提升 = 走与抢约完全相同的内核 {@link SlotBookingCore#book}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromotionService {

    private static final long TTL_SECONDS = 86400L;

    private final StringRedisTemplate redis;
    private final DistributedLockTemplate lockTemplate;
    private final TimeSlotMapper timeSlotMapper;
    private final UserLevelQuery userLevelQuery;
    private final SlotBookingCore bookingCore;
    /** 提升跳过上限外置到 `nearslot.appointment.max-promotion-skip`。 */
    private final org.nearslot.appointment.config.AppointmentProperties props;

    /**
     * 监听余量回流事件并异步提升候补。
     *
     * 用 @EventListener 而非直接调用：取消/超时回收与提升之间不该有编译期依赖，
     * 否则会形成 HoldTimeoutTask ↔ PromotionService ↔ SlotBookingCore 的循环（已实测启动失败）。
     */
    @Async("promotionExecutor")
    @EventListener
    public void onSlotOpened(SlotOpenedEvent event) {
        try {
            Long promoted = promoteNext(event.slotId());
            if (promoted != null) {
                log.info("余量回流：候补已提升 slotId={} userId={}", event.slotId(), promoted);
            }
        } catch (RuntimeException e) {
            log.error("候补提升失败 slotId={}", event.slotId(), e);
        }
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

        int maxSkip = props.getMaxPromotionSkip();
        for (int i = 0; i < maxSkip; i++) {
            Set<String> head = redis.opsForZSet().range(zsetKey, 0, 0);
            if (head == null || head.isEmpty()) return null;
            String userId = head.iterator().next();
            long uid = Long.parseLong(userId);

            // 等级重校验：不满足则跳过该候选（出队但不提升）
            if (minLevel > 0 && userLevelQuery.levelOf(uid) < minLevel) {
                dropCandidate(zsetKey, setKey, statusKey, userId);
                log.info("候补候选等级不足，已跳过 slotId={} userId={} 需 Lv{}", slotId, userId, minLevel);
                continue;
            }

            // 真正的提升：走与抢约完全相同的预扣 + 落单内核
            try {
                Long orderId = bookingCore.book(uid, slotId);
                redis.opsForZSet().remove(zsetKey, userId);
                redis.opsForSet().remove(setKey, userId);
                redis.opsForHash().put(statusKey, userId, String.valueOf(WaitlistService.STATUS_SUCCESS));
                redis.expire(statusKey, TTL_SECONDS, TimeUnit.SECONDS);
                log.info("候补提升成功并已建单 slotId={} userId={} orderId={}", slotId, userId, orderId);
                return uid;
            } catch (BaseException e) {
                // 余量已被抢走 / 时段关闭 / 一人一时段：该候选本轮无望，出队后继续下一位。
                // 这是**正常业务分支**（候补本来就要竞争余量），不是故障。
                dropCandidate(zsetKey, setKey, statusKey, userId);
                log.info("候补候选预扣失败，已跳过 slotId={} userId={} code={}",
                    slotId, userId, e.getCode().getCode());
            }
        }
        log.warn("候补提升达到最大跳过次数 {}，仍有不合格候选 slotId={}", maxSkip, slotId);
        return null;
    }

    /** 出队并清理三键（被跳过的候选）。 */
    private void dropCandidate(String zsetKey, String setKey, String statusKey, String userId) {
        redis.opsForZSet().remove(zsetKey, userId);
        redis.opsForSet().remove(setKey, userId);
        redis.opsForHash().delete(statusKey, userId);
    }
}
