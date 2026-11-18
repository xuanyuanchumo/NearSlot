package org.nearslot.shop.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.common.api.TechnicianRatingUpdater;
import org.nearslot.shop.entity.Technician;
import org.nearslot.shop.mapper.TechnicianMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 技师评分聚合实现（FR-12）。
 *
 * **为什么不能写成一条 UPDATE 自引用**：
 * MySQL 单表 UPDATE 的赋值从左到右生效，后者读到的是**已被改写的新值**。
 * 若写
 *   SET rating_avg = f(rating_avg, rating_count), rating_count = rating_count + 1
 * 第二个赋值用的是新 count；反过来先加 count，公式又会用新 count —— 两种顺序都错。
 *
 * 因此这里用 `SELECT ... FOR UPDATE` 行锁 + Java 侧算好再写：
 * · 并发评价同一技师时串行化，不会丢更新；
 * · 公式只有一处（本类），可被单测直接断言。
 *
 * rating_avg 存**放大 100 倍**的整数（500 = 5.00），避免浮点累积误差。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TechnicianRatingUpdaterImpl implements TechnicianRatingUpdater {

    private static final int SCALE = 100;
    /** 评分区间 1..5（放大 100 倍）——领域不变式，不随运营调参变化。 */
    private static final int MIN_AVG = SCALE * 1;
    private static final int MAX_AVG = SCALE * 5;

    private final TechnicianMapper technicianMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyRating(Long technicianId, int score) {
        if (technicianId == null) return;
        Technician t = lock(technicianId);
        if (t == null) {
            log.warn("技师不存在，跳过评分聚合 technicianId={}", technicianId);
            return;
        }
        int oldCount = t.getRatingCount() == null ? 0 : t.getRatingCount();
        long oldAvg = t.getRatingAvg() == null ? 0 : t.getRatingAvg();
        int newCount = oldCount + 1;
        // 增量均值：新均分 = (旧均分*旧数 + 本次*100) / 新数
        long newAvg = Math.round((oldAvg * oldCount + (long) score * SCALE) / (double) newCount);
        write(technicianId, clamp((int) newAvg), newCount);
        log.info("评分入账 technicianId={} score={} {}->{} count {}->{}",
            technicianId, score, oldAvg, newAvg, oldCount, newCount);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revertRating(Long technicianId, int score) {
        if (technicianId == null) return;
        Technician t = lock(technicianId);
        if (t == null) return;
        int oldCount = t.getRatingCount() == null ? 0 : t.getRatingCount();
        if (oldCount <= 0) return;
        long oldAvg = t.getRatingAvg() == null ? 0 : t.getRatingAvg();
        int newCount = oldCount - 1;
        // 撤到 0 条 -> 0 表示“未评级”。原先回落 500(5.00) 会把只有差评的技师
        // 在撤回后“抬成满分”，属于误导性展示（评审 MAJOR-1）。
        int newAvg = newCount == 0 ? 0
            : (int) Math.round((oldAvg * oldCount - (long) score * SCALE) / (double) newCount);
        // 舍入累积会让均值漂出 [1.00,5.00]（评审实测 5,5,1 撤 1 分算出 501），必须钳制
        write(technicianId, newCount == 0 ? 0 : clamp(newAvg), newCount);
        log.info("评分撤销 technicianId={} score={} {}->{} count {}->{}",
            technicianId, score, oldAvg, newAvg, oldCount, newCount);
    }

    /** 钳制到 [1.00, 5.00]（放大 100 倍后的整数区间）。 */
    private int clamp(int avg) {
        return Math.max(MIN_AVG, Math.min(MAX_AVG, avg));
    }

    /** 行锁读取（并发评价串行化的关键）。 */
    private Technician lock(Long technicianId) {
        // 字符串列名而非 lambda：lambda 依赖 TableInfo 缓存，纯单测下不可用
        return technicianMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Technician>()
                .eq("id", technicianId)
                .last("FOR UPDATE"));
    }

    private void write(Long technicianId, int ratingAvg, int ratingCount) {
        technicianMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Technician>()
                .eq("id", technicianId)
                .set("rating_avg", ratingAvg)
                .set("rating_count", ratingCount));
    }
}
