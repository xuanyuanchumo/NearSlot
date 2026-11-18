package org.nearslot.appointment.event;

/**
 * 余量回流事件（slot.opened）。
 *
 * **为什么用事件而不是直接调用（2026-11-17 审计修复）**：
 * 余量回流后需要触发候补提升，而提升本身要复用抢约内核 {@code SlotBookingCore}，
 * 内核又要投递 Hold 超时消息（依赖 {@code HoldTimeoutTask}），
 * 超时回收成功后又需要触发提升 —— 直接互相注入会形成
 * HoldTimeoutTask ↔ PromotionService 的**循环依赖，Spring 启动直接失败**（已实测）。
 *
 * 用事件把「谁释放了余量」与「谁去提升候补」解耦：
 *   · 释放方（取消 / 超时回收）只负责 publish，不关心有没有候补；
 *   · 提升方（PromotionService）监听事件，异步提升，失败不影响释放方的业务事务。
 * 这也正好对齐 research/08 §5「取消端点只做标记可用 + 发 slot.opened 事件」。
 *
 * @param slotId 有余量回流的时段
 */
public record SlotOpenedEvent(long slotId) {
}
