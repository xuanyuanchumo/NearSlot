package org.nearslot.appointment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.appointment.entity.TimeSlot;
import org.nearslot.appointment.mapper.TimeSlotMapper;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.framework.cache.RedisConstants;
import org.nearslot.framework.id.IdGenerator;
import org.nearslot.framework.lock.DistributedLockTemplate;
import org.nearslot.framework.mq.CacheInvalidationPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 时段发布（FR-2.2）。
 *
 * 一次发布要做三件事：
 *   1. 落库时段定义（幂等：按 technicianId+startsAt 唯一约束）；
 *   2. 预热 Redis 余量（Redis 主、DB 兜底，schema.md §2）；
 *   3. **广播缓存失效**，让所有实例的本地 Caffeine L1 同步失效。
 *
 * 第 3 步是关键：若只删本实例缓存，其他实例仍返回旧时段数据，
 * 用户会看到"已关闭的时段仍可预约"（跨实例脏读）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlotPublishService {

    private final TimeSlotMapper timeSlotMapper;
    private final StringRedisTemplate redis;
    private final CacheInvalidationPublisher invalidationPublisher;
    private final DistributedLockTemplate lockTemplate;
    private final ReminderService reminderService;
    private final org.nearslot.framework.cache.CacheManager cacheManager;

    /** 发布时段（幂等）。 */
    @Transactional(rollbackFor = Exception.class)
    public Long publish(Long shopId, Long technicianId, Long serviceItemId,
                        LocalDateTime startsAt, LocalDateTime endsAt,
                        int capacity, Integer minLevel) {
        if (capacity <= 0) throw new BaseException(BaseCode.INTERNAL_ERROR, "容量必须大于 0");
        if (!endsAt.isAfter(startsAt)) throw new BaseException(BaseCode.INTERNAL_ERROR, "结束时间必须晚于开始时间");

        // 唯一约束 uq_slot_occupy(technician_id, starts_at) 兜底防重复发布
        TimeSlot slot = new TimeSlot();
        slot.setId(IdGenerator.nextId());
        slot.setShopId(shopId);
        slot.setTechnicianId(technicianId);
        slot.setServiceItemId(serviceItemId == null ? 0L : serviceItemId);
        slot.setStartsAt(startsAt);
        slot.setEndsAt(endsAt);
        slot.setTzId("Asia/Shanghai");
        slot.setCapacity(capacity);
        slot.setStatus(1);
        slot.setVersion(0);
        slot.setMinLevel(minLevel == null ? 0 : minLevel);
        timeSlotMapper.insert(slot);

        // 预热余量（Redis 主）
        redis.opsForValue().set(RedisConstants.SLOT_STOCK + slot.getId(),
            String.valueOf(capacity), 24, TimeUnit.HOURS);

        // 广播失效：写锁串行化，避免与其他实例的重建并发
        lockTemplate.withWriteLock(RedisConstants.LOCK + "slot:publish:" + slot.getId(), () -> {
            boolean okStock = invalidationPublisher.publish(RedisConstants.SLOT_STOCK + slot.getId());
            boolean okShop = invalidationPublisher.publish(RedisConstants.CACHE_SHOP + shopId);
            if (!okStock || !okShop) {
                // MQ 不可用：降级为本地失效，保证本实例不会返回旧缓存；
                // 其他实例依赖 TTL 自然过期（最终一致），并把风险写日志便于告警
                cacheManager.invalidate(RedisConstants.SLOT_STOCK + slot.getId());
                cacheManager.invalidate(RedisConstants.CACHE_SHOP + shopId);
                log.warn("失效广播不可用，已降级为本地失效（其他实例依赖 TTL）slotId={}", slot.getId());
            }
            return null;
        });

        // FR-3.4：安排开抢前 2 分钟提醒
        reminderService.scheduleForSlot(slot.getId());

        log.info("时段已发布 slotId={} shopId={} techId={} capacity={} minLevel={}",
            slot.getId(), shopId, technicianId, capacity, slot.getMinLevel());
        return slot.getId();
    }

    /** 关闭时段：状态置 2 + 删余量键 + 广播。 */
    @Transactional(rollbackFor = Exception.class)
    public void close(Long slotId) {
        TimeSlot slot = timeSlotMapper.selectById(slotId);
        if (slot == null) throw new BaseException(BaseCode.NOT_FOUND);
        slot.setStatus(2);
        timeSlotMapper.updateById(slot);

        redis.delete(RedisConstants.SLOT_STOCK + slotId);
        lockTemplate.withWriteLock(RedisConstants.LOCK + "slot:close:" + slotId, () -> {
            if (!invalidationPublisher.publish(RedisConstants.SLOT_STOCK + slotId)) {
                cacheManager.invalidate(RedisConstants.SLOT_STOCK + slotId);
                log.warn("关闭时段广播不可用，已降级本地失效 slotId={}", slotId);
            }
            return null;
        });
        log.info("时段已关闭 slotId={}", slotId);
    }
}
