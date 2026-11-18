package org.nearslot.appointment.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.appointment.entity.TimeSlot;
import org.nearslot.appointment.mapper.TimeSlotMapper;
import org.nearslot.framework.cache.RedisConstants;
import org.nearslot.framework.delayqueue.RedissonDelayQueue;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

/**
 * 开抢提醒（FR-3.4）。
 *
 * 业务规则：时段开始前 **2 分钟** 提醒已候补/关注的用户；
 * 去重窗口 **300s**，同一用户同一时段只提醒一次。
 *
 * 实现（复用 framework 的 RedissonDelayQueue，ADR-016 成熟库优先）：
 *   发布时段时计算 delay = 距开始时间 - 2min，投递延迟消息；
 *   到期消费即为提醒时刻，无需轮询扫描。
 *
 * 边界：若发布时距开抢已不足 2 分钟（delay<=0），**立即提醒**而非丢弃，
 * 否则用户会完全收不到通知（这是容易漏掉的分支）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderService {

    /** 提前量：2 分钟。 */
    /** 提前量与去重窗口由 `nearslot.appointment.reminder-*` 决定。 */
    /** 去重窗口：300s（research/02 对齐 hmdp-plus dedup.window.seconds=300）。 */

    /** 延迟队列名。 */
    public static final String REMINDER_QUEUE = "nearslot:delay:reminder";

    private final TimeSlotMapper timeSlotMapper;
    private final RedissonDelayQueue delayQueue;
    private final StringRedisTemplate redis;
    /** 提前量与去重窗口外置到 `nearslot.appointment.reminder-*`。 */
    private final org.nearslot.appointment.config.AppointmentProperties props;

    /** 时段发布后调用：为此时段安排开抢提醒。 */
    public void scheduleForSlot(Long slotId) {
        TimeSlot slot = timeSlotMapper.selectById(slotId);
        if (slot == null || slot.getStartsAt() == null) return;

        long startEpoch = slot.getStartsAt()
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long now = System.currentTimeMillis();
        long delaySeconds = (startEpoch - now) / 1000 - props.getReminderAheadSeconds();

        if (delaySeconds <= 0) {
            // 已不足 2 分钟（或已过开始时间）：立即触发一次，避免完全收不到
            log.info("时段即将/已开始，立即触发提醒 slotId={}", slotId);
            fireReminder(slotId);
        } else {
            delayQueue.offer(REMINDER_QUEUE, String.valueOf(slotId), delaySeconds);
            log.info("开抢提醒已排期 slotId={} delay={}s（提前 {}s）", slotId, delaySeconds, props.getReminderAheadSeconds());
        }
    }

    /** 启动消费者：到期即执行提醒。 */
    public void startConsumer() {
        delayQueue.startConsumer(REMINDER_QUEUE, payload -> {
            try {
                long slotId = Long.parseLong(String.valueOf(payload));
                fireReminder(slotId);
            } catch (RuntimeException e) {
                log.error("开抢提醒处理失败 payload={}", payload, e);
            }
        });
        log.info("开抢提醒消费者已启动 queue={}", REMINDER_QUEUE);
    }

    /**
     * 执行提醒：遍历候补用户，按去重窗口逐人发送。
     *
     * 当前实现只记录日志（无短信/推送网关，与 FR-1.1 同一限制）；
     * 去重键写入 Redis，保证重复触发不会重复打扰。
     */
    void fireReminder(long slotId) {
        var candidates = redis.opsForZSet()
            .range(RedisConstants.SUBSCRIBE_ZSET + slotId, 0, -1);
        if (candidates == null || candidates.isEmpty()) {
            log.debug("时段无候补用户，无需提醒 slotId={}", slotId);
            return;
        }
        int sent = 0;
        for (String userId : candidates) {
            String dedupKey = RedisConstants.PREFIX + "reminder:dedup:" + slotId + ":" + userId;
            Boolean first = redis.opsForValue()
                .setIfAbsent(dedupKey, "1", props.getReminderDedupWindowSeconds(), TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(first)) {
                // TODO(Phase 3+)：接入短信/APP 推送网关
                log.info("【开抢提醒】slotId={} userId={}（2 分钟后开始）", slotId, userId);
                sent++;
            }
        }
        log.info("开抢提醒完成 slotId={} 共发送 {} 人（去重窗口 {}s）", slotId, sent, props.getReminderDedupWindowSeconds());
    }
}
