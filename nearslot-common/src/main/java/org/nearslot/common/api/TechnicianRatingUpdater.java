package org.nearslot.common.api;

/**
 * 技师评分聚合契约（FR-12）。
 *
 * 为什么放在 common：评价写入在 content 模块，而 technician 表属于 shop 模块，
 * content **不得**依赖 shop（C-ARCH-01）。因此把"更新评分聚合"抽成契约，
 * 由 shop 实现（并负责并发安全），content 只调用抽象。
 *
 * 为什么不直接让 content 写 technician 表：跨模块写同一张表会让模块边界失效，
 * 且评分公式的并发安全（读旧值→算新值）必须与表的所有权放在一起。
 */
public interface TechnicianRatingUpdater {

    /**
     * 新增一条评分（原子，内部加行锁）。
     *
     * @param technicianId 技师 ID（null 时直接返回，如餐品预约）
     * @param score        本次评分（1..5）
     */
    void applyRating(Long technicianId, int score);

    /**
     * 撤销一条评分（删评价时调用，内部加行锁）。
     *
     * @param technicianId 技师 ID（null 时忽略）
     * @param score        被撤销的评分（1..5）
     */
    void revertRating(Long technicianId, int score);
}
