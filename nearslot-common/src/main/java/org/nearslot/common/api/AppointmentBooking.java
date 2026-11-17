package org.nearslot.common.api;

/**
 * 预约命令契约（跨模块写操作）。
 *
 * 关键约束（design §9.4）：agent 不重复实现业务校验——余量/等级/时间轴冲突
 * 全部由 provider 侧的 Lua 三检查裁决，agent 只负责把 BaseCode 翻译成用户话术。
 * 写操作必须幂等（provider 侧 @Idempotent 兜底，ADR-003）。
 */
public interface AppointmentBooking {

    /** 抢约。返回 orderId；失败抛 BaseException(BaseCode)。 */
    Long book(Long userId, Long slotId);

    /** 取消。provider 侧做状态机 + 回补反转 + 对账。 */
    void cancel(Long userId, Long orderId);

    /** 加入候补。返回排位（从 1 开始）。 */
    long subscribeSlot(Long userId, Long slotId);
}
