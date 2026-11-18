package org.nearslot.shop.api;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.shop.entity.Technician;
import org.nearslot.shop.mapper.TechnicianMapper;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 技师评分聚合单测（FR-12）。
 *
 * 增量均值公式写错不会报错，只会让评分慢慢漂移，而评分直接影响"选哪个技师"——
 * 属于典型的"错了很久才发现"。这里把公式与边界逐条钉死。
 *
 * 断言方式说明：UpdateWrapper 生成的 SQL 是 `rating_avg=#{ew.paramNameValuePairs.MPGENVAL1}`，
 * 具体数值在 paramNameValuePairs 里，所以断言**取值**而不是拼好的 SQL 文本。
 *
 * 约定：rating_avg 存放大 100 倍的整数（500 = 5.00）。
 */
class TechnicianRatingUpdaterTest {

    private TechnicianMapper mapper;
    private TechnicianRatingUpdaterImpl updater;

    @BeforeEach
    void setUp() {
        mapper = mock(TechnicianMapper.class);
        updater = new TechnicianRatingUpdaterImpl(mapper);
    }

    private Technician tech(Integer avg, Integer count) {
        Technician t = new Technician();
        t.setId(2L);
        t.setRatingAvg(avg);
        t.setRatingCount(count);
        return t;
    }

    /** 执行一次聚合，返回写入的参数值集合（[rating_avg, rating_count]）。 */
    @SuppressWarnings("unchecked")
    private Collection<Object> writtenValues(Integer avg, Integer count, boolean apply, int score) {
        when(mapper.selectOne(any())).thenReturn(tech(avg, count));
        if (apply) updater.applyRating(2L, score); else updater.revertRating(2L, score);
        var captor = org.mockito.ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        return captor.getValue().getParamNameValuePairs().values();
    }

    private static boolean has(Collection<Object> vals, int expected) {
        return vals.stream().anyMatch(v -> v instanceof Number n && n.intValue() == expected);
    }

    @Test
    @DisplayName("首评 5 分 -> 均分 500，条数 1")
    void firstRating() {
        var vals = writtenValues(500, 0, true, 5);
        assertTrue(has(vals, 500), "首评 5 分应为 500，实际：" + vals);
        assertTrue(has(vals, 1), "条数应为 1，实际：" + vals);
    }

    @Test
    @DisplayName("已有 4.00 / 2 条，新增 5 分 -> (400*2+500)/3 = 433")
    void incrementalAverage() {
        var vals = writtenValues(400, 2, true, 5);
        assertTrue(has(vals, 433), "应为 433，实际：" + vals);
        assertTrue(has(vals, 3), "条数应为 3，实际：" + vals);
    }

    @Test
    @DisplayName("首评 1 分 -> 均分 100（1.00）")
    void firstBadRating() {
        var vals = writtenValues(500, 0, true, 1);
        assertTrue(has(vals, 100), "首评 1 分应为 100，实际：" + vals);
    }

    @Test
    @DisplayName("撤销唯一一条 -> 回落默认 500 且条数 0（避免除零/负数）")
    void revertToEmpty() {
        var vals = writtenValues(100, 1, false, 1);
        assertTrue(has(vals, 500), "撤到 0 条应回落 500，实际：" + vals);
        assertTrue(has(vals, 0), "条数应为 0，实际：" + vals);
    }

    @Test
    @DisplayName("撤销一条 5 分（原 4.33 / 3 条）-> 回到 4.00 / 2 条")
    void revertRestoresAverage() {
        var vals = writtenValues(433, 3, false, 5);
        assertTrue(has(vals, 400), "撤销后应回到 400，实际：" + vals);
        assertTrue(has(vals, 2), "条数应为 2，实际：" + vals);
    }

    @Test
    @DisplayName("technicianId 为 null（餐品预约）-> 不动表")
    void nullTechnicianIsNoop() {
        updater.applyRating(null, 5);
        updater.revertRating(null, 5);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("技师不存在 -> 不抛异常（不阻断评价提交）")
    void missingTechnicianIsTolerated() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertDoesNotThrow(() -> updater.applyRating(99L, 5));
        verify(mapper, never()).update(any(), any());
    }
}
