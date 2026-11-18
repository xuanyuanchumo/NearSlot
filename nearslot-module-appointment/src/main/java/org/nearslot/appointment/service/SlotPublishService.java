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
 *   1. 落库时段定义（幂等：按 serviceStaffId+startsAt 唯一约束）；
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
    private final org.nearslot.common.api.StaffSkillQuery staffSkillQuery;
    private final org.nearslot.framework.cache.CacheManager cacheManager;
    /** 时段查询上限外置到 `nearslot.appointment.slot-query-limit`。 */
    private final org.nearslot.appointment.config.AppointmentProperties props;

    /** 发布时段（幂等）。 */
    @Transactional(rollbackFor = Exception.class)
    public Long publish(Long shopId, Long serviceStaffId, Long serviceItemId,
                        LocalDateTime startsAt, LocalDateTime endsAt,
                        int capacity, Integer minLevel) {
        if (capacity <= 0) throw new BaseException(BaseCode.INTERNAL_ERROR, "容量必须大于 0");
        if (!endsAt.isAfter(startsAt)) throw new BaseException(BaseCode.INTERNAL_ERROR, "结束时间必须晚于开始时间");

        // 唯一约束 uq_slot_occupy(technician_id, starts_at) 兜底防重复发布
        TimeSlot slot = new TimeSlot();
        slot.setId(IdGenerator.nextId());
        slot.setShopId(shopId);
        slot.setServiceStaffId(serviceStaffId);
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
        //
        // **只广播 service 层旧缓存（shop 详情），绝不失效刚预热的余量键**。
        // 实测踩坑（严重）：此前把 SLOT_STOCK 也一起 invalidate，导致
        // "预热 -> 立刻删除" 的自毁逻辑，抢约永远返回 10004 无库存键。
        // 新键本身就是最新值，失效它没有任何意义，只会删掉正确数据。
        lockTemplate.withWriteLock(RedisConstants.LOCK + "slot:publish:" + slot.getId(), () -> {
            boolean ok = invalidationPublisher.publish(RedisConstants.CACHE_SHOP + shopId);
            if (!ok) {
                // MQ 不可用：降级为本实例失效 shop 缓存；其他实例依赖 TTL（最终一致）
                cacheManager.invalidate(RedisConstants.CACHE_SHOP + shopId);
                log.warn("失效广播不可用，已降级为本地失效（其他实例依赖 TTL）shopId={}", shopId);
            }
            // 广播已预热余量键这件事本身（通知其他实例刷新，而非删除）
            invalidationPublisher.publish(RedisConstants.PREFIX + "slot:published:" + slot.getId());
            return null;
        });

        // FR-3.4：安排开抢前 2 分钟提醒
        reminderService.scheduleForSlot(slot.getId());

        log.info("时段已发布 slotId={} shopId={} techId={} capacity={} minLevel={}",
            slot.getId(), shopId, serviceStaffId, capacity, slot.getMinLevel());
        return slot.getId();
    }

    /**
     * 查询可约时段（前端选时段用）。
     * 返回时段基本信息 + 实时余量（从 Redis 读，读不到回落 capacity）。
     */
    public java.util.List<java.util.Map<String, Object>> listAvailable(Long shopId, String date, Long serviceItemId) {
        // 按服务项筛选能做该项目的服务者（业务域澄清：先选项目再选人）
        java.util.Set<Long> allowedStaff = null;
        if (serviceItemId != null) {
            allowedStaff = staffSkillQuery.staffIdsByItem(serviceItemId);
            if (allowedStaff == null || allowedStaff.isEmpty()) return java.util.List.of();
        }
        // **2026-11-17 修正（正确性缺陷）**：此前先 `LIMIT 100` 取回再在内存里按日期过滤——
        // 店铺历史时段一旦超过 100 条，查未来日期的页面会**稳定返回空**（被更早的 100 条占满）。
        // 现在把日期条件下推到 SQL，并用 MP Page 限制返回量。
        var q = com.baomidou.mybatisplus.core.toolkit.Wrappers.<TimeSlot>lambdaQuery()
            .eq(TimeSlot::getShopId, shopId)
            .eq(TimeSlot::getStatus, 1);
        if (date != null && !date.isBlank()) {
            java.time.LocalDate d = java.time.LocalDate.parse(date);
            q.ge(TimeSlot::getStartsAt, d.atStartOfDay())
             .lt(TimeSlot::getStartsAt, d.plusDays(1).atStartOfDay());
        }
        if (allowedStaff != null) {
            q.in(TimeSlot::getServiceStaffId, allowedStaff);
        }
        q.orderByAsc(TimeSlot::getStartsAt);
        java.util.List<TimeSlot> slots = timeSlotMapper.selectList(
            q.last("LIMIT " + props.getSlotQueryLimit()));
        // 批量取余量，避免循环内单键 GET（N+1 往返）
        java.util.List<String> stockKeys = slots.stream()
            .map(s -> RedisConstants.SLOT_STOCK + s.getId()).toList();
        java.util.List<String> stocks = stockKeys.isEmpty() ? java.util.List.of()
            : redis.opsForValue().multiGet(stockKeys);
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            TimeSlot s = slots.get(i);
            // 防御性二次校验：SQL 已用 in(...) 过滤，但技师技能可能在查询后被解除绑定，
            // 或上层传入的 allowedStaff 来自缓存。这里再拦一次，代价可忽略，
            // 避免"用户选了肩颈推拿却排给只会美甲的技师"（business-domain.md §3.2）。
            if (allowedStaff != null && !allowedStaff.contains(s.getServiceStaffId())) continue;
            String stock = stocks == null || i >= stocks.size() ? null : stocks.get(i);
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", String.valueOf(s.getId()));       // 字符串防精度丢失（ADR-012）
            m.put("shopId", String.valueOf(s.getShopId()));
            m.put("serviceStaffId", String.valueOf(s.getServiceStaffId()));
            m.put("startsAt", s.getStartsAt());
            m.put("endsAt", s.getEndsAt());
            m.put("capacity", s.getCapacity());
            m.put("remaining", stock != null ? Integer.parseInt(stock) : s.getCapacity());
            m.put("minLevel", s.getMinLevel());
            out.add(m);
        }
        return out;
    }

    /**
     * 取时段归属店铺（鉴权用）。
     *
     * 为什么单独暴露：关闭时段的请求体里没有 shopId，
     * 控制器无法自行判断归属，必须先把 slotId 翻译成 shopId 再做闸。
     *
     * @throws org.nearslot.common.exception.BaseException 时段不存在时 404
     */
    public Long shopIdOfSlot(Long slotId) {
        TimeSlot slot = timeSlotMapper.selectById(slotId);
        if (slot == null) throw new BaseException(BaseCode.NOT_FOUND, "时段不存在 id=" + slotId);
        return slot.getShopId();
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
