package org.nearslot.common.api;

import java.time.LocalDateTime;

/**
 * 履约订单校验契约（FR-7 / ADR-026 / FR-12）。
 *
 * 为什么放在 common：内容模块（content）发布笔记、提交评价时需要确认
 * "该订单已完成且属于当前用户"，但 **content 不能依赖 appointment 模块**
 * （C-ARCH-01 铁律）。因此由 appointment 实现本接口、注册为 Spring Bean，
 * content 侧只依赖抽象。
 *
 * 这与既有的 UserLevelQuery / StaffSkillQuery / OrderQuery 是同一套模式。
 */
public interface CompletedOrderQuery {

    /**
     * 判断订单是否"已完成且属于该用户"。
     *
     * @param userId  当前用户
     * @param orderId 订单号
     * @return true = 可凭此订单发布笔记 / 提交评价
     */
    boolean isCompletedBy(Long userId, Long orderId);

    /** 取订单归属店铺（用于预热笔记的 shopId）。订单不存在返回 null。 */
    Long shopIdOf(Long orderId);

    /**
     * 取"可评价"订单的履约快照（FR-12）。
     *
     * @return null 表示订单不存在、不属于该用户或未完成
     */
    ReviewableOrder reviewableOf(Long userId, Long orderId);

    /**
     * 履约快照（评价所需的字段）。
     *
     * 用 record 而不是 Map：跨模块契约必须类型化，否则调用方只能靠 key 猜，
     * 一旦实现侧改名就是运行期 NPE（本项目已因 Map 契约踩过坑）。
     *
     * @param orderId      订单号
     * @param shopId       归属店铺
     * @param technicianId 履约执行者（可空）
     * @param serviceItemId 服务项（可空）
     * @param finishedAt   完成时间（用于评价时限判定）
     */
    record ReviewableOrder(Long orderId, Long shopId, Long technicianId,
                           Long serviceItemId, LocalDateTime finishedAt) {
    }
}
