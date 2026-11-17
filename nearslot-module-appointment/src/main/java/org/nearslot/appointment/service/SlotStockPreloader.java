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

    /** 预热单个时段余量（发布时段时调用）。 */
    public void preload(TimeSlot slot) {
        redis.opsForValue().set(
            RedisConstants.SLOT_STOCK + slot.getId(),
            String.valueOf(slot.getCapacity() == null ? 1 : slot.getCapacity()));
    }

    private final org.nearslot.appointment.service.HoldTimeoutTask holdTimeoutTask;
    private final org.nearslot.appointment.service.ReminderService reminderService;

    @Bean
    public ApplicationRunner delayQueueConsumerStarter() {
        return args -> {
            holdTimeoutTask.startConsumer();
            reminderService.startConsumer();
        };
    }

    @Bean
    public ApplicationRunner slotStockWarmUp() {
        return args -> {
            try {
                List<TimeSlot> slots = timeSlotMapper.selectList(null);
                slots.stream()
                    .filter(s -> s.getStatus() != null && s.getStatus() == 1)
                    .forEach(this::preload);
                log.info("时段余量预热完成：{} 个时段", slots.size());
            } catch (RuntimeException e) {
                log.warn("时段余量预热失败（不影响启动，需人工检查）: {}", e.toString());
            }
        };
    }
}
