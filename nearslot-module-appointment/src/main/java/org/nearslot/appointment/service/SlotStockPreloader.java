package org.nearslot.appointment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.appointment.entity.TimeSlot;
import org.nearslot.appointment.mapper.TimeSlotMapper;
import org.nearslot.framework.cache.RedisConstants;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * 时段余量预热（Redis 主、DB 兜底，schema.md §2）。
 *
 * 实测踩坑：未预热时抢约返回 10004「无库存键」，链路看似失败实为缺预热。
 * 生产应由「时段发布事件」触发增量预热；此处提供启动全量预热 + 可复用的单条预热方法。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SlotStockPreloader {

    private final TimeSlotMapper timeSlotMapper;
    private final StringRedisTemplate redis;

    /**
     * 预热单个时段余量。
     *
     * **2026-11-17 审计修复（Blocker）**：此前无条件 SET 为 capacity，
     * 而启动时又会全量重跑一遍 —— 每次重启都把**已售出**的余量重置回满值，
     * 直接导致超卖（订单已确认，库存却回到 capacity）。
     *
     * 现改为两种语义：
     *   · 启动预热走 {@link #preloadIfAbsent}（SETNX）——只补缺失的键，绝不覆盖在售余量；
     *   · 发布新时段走 {@link #preload}（新键，覆盖无风险）。
     */
    public void preload(TimeSlot slot) {
        redis.opsForValue().set(
            RedisConstants.SLOT_STOCK + slot.getId(),
            String.valueOf(slot.getCapacity() == null ? 1 : slot.getCapacity()));
    }

    /**
     * 启动预热：仅当键不存在时写入（NX）。
     *
     * @return true=写入，false=键已存在（保持在售余量不动）
     */
    public boolean preloadIfAbsent(TimeSlot slot) {
        Boolean written = redis.opsForValue().setIfAbsent(
            RedisConstants.SLOT_STOCK + slot.getId(),
            String.valueOf(slot.getCapacity() == null ? 1 : slot.getCapacity()));
        return Boolean.TRUE.equals(written);
    }

    private final org.nearslot.appointment.service.HoldTimeoutTask holdTimeoutTask;
    private final org.nearslot.appointment.service.ReminderService reminderService;
    private final org.nearslot.appointment.service.PrepareReminderService prepareReminderService;

    @Bean
    public ApplicationRunner delayQueueConsumerStarter() {
        return args -> {
            holdTimeoutTask.startConsumer();
            reminderService.startConsumer();
            prepareReminderService.startConsumer();
        };
    }

    @Bean
    public ApplicationRunner slotStockWarmUp() {
        return args -> {
            try {
                List<TimeSlot> slots = timeSlotMapper.selectList(null);
                slots.stream()
                    .filter(s -> s.getStatus() != null && s.getStatus() == 1)
                    // 启动预热必须用 NX：绝不能把已售出的余量重置回 capacity（超卖根因）
                    .filter(this::preloadIfAbsent)
                    .count();
                log.info("时段余量预热完成：{} 个时段", slots.size());
            } catch (RuntimeException e) {
                log.warn("时段余量预热失败（不影响启动，需人工检查）: {}", e.toString());
            }
        };
    }
}
