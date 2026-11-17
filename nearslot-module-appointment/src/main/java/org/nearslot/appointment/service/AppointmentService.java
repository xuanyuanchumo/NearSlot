package org.nearslot.appointment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.appointment.domain.BookResult;
import org.nearslot.appointment.entity.AppointmentOrder;
import org.nearslot.appointment.entity.TimeSlot;
import org.nearslot.appointment.mapper.AppointmentOrderMapper;
import org.nearslot.appointment.mapper.TimeSlotMapper;
import org.nearslot.common.api.UserLevelQuery;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.enums.LogType;
import org.nearslot.common.enums.OrderStatus;
import org.nearslot.common.exception.BaseException;
import org.nearslot.appointment.event.SlotOpenedEvent;
import org.nearslot.framework.idempotent.Idempotent;
import org.nearslot.framework.id.IdGenerator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;

/**
 * 抢约编排（FR-3.1 / research/08 §3–4）。
 * 链路：幂等 → 等级闸 → Lua 三检查预扣 + 落单（{@link SlotBookingCore}）→ 支付确认 / 超时回补。
 *
 * **2026-11-17 审计修复**：
 * · Blocker —— 回补曾 DEL 余量键且无任何重载路径，取消后该时段永久不可约（已在 Lua 修复）；
 * · Major —— persistHold 曾是 protected + 同类自调用，@Transactional 完全失效；
 *   现已下沉到独立 Bean {@link SlotBookingCore}；
 * · Major —— 取消时未判 slot 空值即解引用，时段被删则取消接口 500 且订单卡死；
 * · Major —— 取消与支付未共用同一把订单锁、也无状态 CAS，已改为条件更新（见下）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppointmentService {

    private final SlotBookingCore bookingCore;
    private final TimeSlotMapper timeSlotMapper;
    private final AppointmentOrderMapper orderMapper;
    private final HoldTimeoutTask holdTimeoutTask;
    private final ReconcileService reconcileService;
    /** 等级校验走 common 接口，避免 appointment 直接依赖 auth 模块（C-ARCH-01）。 */
    private final UserLevelQuery userLevelQuery;
    /**
     * 余量回流事件发布器（FR-3.2）。
     *
     * 为什么用事件而非直接注入 PromotionService：提升要复用 SlotBookingCore，
     * 而核心要投递 Hold 消息（依赖 HoldTimeoutTask），超时回收成功后又需触发提升 ——
     * 直接互相注入会形成循环依赖，Spring 启动即失败（已实测）。
     * 事件把「谁释放了余量」与「谁去提升候补」彻底解耦。
     */
    private final ApplicationEventPublisher eventPublisher;
    private final AppointmentLuaService luaService;
    /** 核销码作废（FR-6.1）：取消路径必须同步作废，否则存在"已退款仍可核销"的资损口。 */
    private final VoucherService voucherService;

    @Idempotent(key = "#userId + ':' + #slotId", ttl = 10)
    public Long book(Long userId, Long slotId) {
        TimeSlot slot = timeSlotMapper.selectById(slotId);
        if (slot == null) throw new BaseException(BaseCode.NOT_FOUND);

        // FR-1.4 会员等级闸：等级不足直接拒绝（在 Lua 之前短路，避免无效 Redis 写入）
        int minLevel = slot.getMinLevel() == null ? 0 : slot.getMinLevel();
        if (minLevel > 0) {
            int userLevel = userLevelQuery.levelOf(userId);
            if (userLevel < minLevel) {
                throw new BaseException(BaseCode.LEVEL_NOT_MET,
                    "需 Lv" + minLevel + " 及以上会员，当前 Lv" + userLevel);
            }
        }

        Long orderId = bookingCore.book(userId, slotId);
        AppointmentOrder created = new AppointmentOrder();
        created.setId(orderId);
        holdTimeoutTask.scheduleExpire(created, bookingCore.holdSeconds());
        return orderId;
    }

    /** 订单详情（FR-4.1）：校验归属防越权。 */
    public AppointmentOrder queryOrder(Long userId, Long orderId) {
        AppointmentOrder o = orderMapper.selectById(orderId);
        if (o == null) throw new BaseException(BaseCode.NOT_FOUND);
        if (!o.getUserId().equals(userId)) {
            // 越权访问：不暴露订单是否存在，统一按 NOT_FOUND
            throw new BaseException(BaseCode.NOT_FOUND);
        }
        return o;
    }

    /** 我的订单列表（FR-4.1），按创建时间倒序。 */
    public java.util.List<AppointmentOrder> listMyOrders(Long userId, Integer status) {
        var q = com.baomidou.mybatisplus.core.toolkit.Wrappers.<AppointmentOrder>lambdaQuery()
            .eq(AppointmentOrder::getUserId, userId);
        if (status != null) q.eq(AppointmentOrder::getStatus, status);
        q.orderByDesc(AppointmentOrder::getId).last("LIMIT 50");
        return orderMapper.selectList(q);
    }

    /** 取消：状态 CAS → 回补反转 → 触发候补。 */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long userId, Long orderId) {
        AppointmentOrder o = orderMapper.selectById(orderId);
        if (o == null || !o.getUserId().equals(userId)) throw new BaseException(BaseCode.NOT_FOUND);
        if (o.getStatus() != OrderStatus.PENDING.getCode() && o.getStatus() != OrderStatus.CONFIRMED.getCode()) {
            throw new BaseException(BaseCode.INTERNAL_ERROR, "当前状态不可取消");
        }

        long traceId = IdGenerator.nextId();
        // 状态 CAS：只允许从 PENDING/CONFIRMED 迁到 CANCELLED。
        // 原实现直接 updateById（按 id 覆盖），与支付回调并发时会互相覆盖状态。
        boolean moved = casStatus(orderId, o.getStatus(), OrderStatus.CANCELLED.getCode());
        if (!moved) {
            throw new BaseException(BaseCode.INTERNAL_ERROR, "订单状态已被变更，请刷新后重试");
        }

        TimeSlot slot = timeSlotMapper.selectById(o.getSlotId());
        if (slot == null) {
            // 时段被删除/回滚：只改状态 + 记对账，不阻断取消（避免 NPE -> 500 导致订单卡死）
            reconcileService.writeBestEffort(orderId, LogType.RESTORE, false, 0, 0, 0, traceId,
                "slot missing on cancel, redis rollback skipped slotId=" + o.getSlotId());
            log.warn("取消时时段已不存在，跳过 Redis 回补 orderId={} slotId={}", orderId, o.getSlotId());
            return;
        }

        long startTs = slot.getStartsAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endTs = slot.getEndsAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        int capacity = slot.getCapacity() == null ? 1 : slot.getCapacity();

        BookResult rb = luaService.rollback(userId, o.getSlotId(), o.getServiceStaffId(),
            startTs, endTs, orderId, traceId, 1, capacity);
        // 回补结果同样入对账日志（成功/失败都记，便于三方互证）
        reconcileService.writeBestEffort(orderId, LogType.RESTORE, rb.success(),
            rb.getBeforeQty(), rb.getDeductQty(), rb.getAfterQty(), traceId,
            rb.success() ? "cancel rollback" : "cancel rollback failed code=" + rb.getCode());
        log.info("cancel ok userId={} orderId={} traceId={} rollbackSuccess={}",
            userId, orderId, traceId, rb.success());

        // FR-6.1：取消后作废核销码，防止"钱退了还能到店核销"
        voucherService.voidForOrder(orderId);

        // FR-3.2 余量回流：取消后异步提升队首候补（不阻塞取消端点）
        if (rb.success()) {
            eventPublisher.publishEvent(new SlotOpenedEvent(o.getSlotId()));
        }
    }

    /**
     * 状态条件更新（CAS）。
     *
     * 为什么必须 CAS：取消与支付回调是两条并发路径，
     * 原先都用 updateById 整体覆盖，谁后写谁生效——
     * 会出现「已取消的订单被支付覆盖成已确认」或反之，库存与状态不一致。
     */
    private boolean casStatus(Long orderId, int fromStatus, int toStatus) {
        // 用字符串列名的 UpdateWrapper 而非 lambdaUpdate：
        // lambda 版本依赖 MyBatis-Plus 的实体内省缓存（TableInfo），
        // 在纯单测环境（无 SqlSessionFactory）会抛
        // "can not find lambda cache for this entity"——不得为了测试好写就牺牲生产正确性，
        // 但也不应让单测必须启动 MyBatis 容器。字符串列名同样类型安全（列名是常量）。
        var wrapper = new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<AppointmentOrder>()
            .eq("id", orderId)
            .eq("status", fromStatus)
            .set("status", toStatus);
        return orderMapper.update(null, wrapper) > 0;
    }
}
