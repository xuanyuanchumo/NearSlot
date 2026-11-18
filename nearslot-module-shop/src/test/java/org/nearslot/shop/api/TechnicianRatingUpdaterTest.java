package org.nearslot.shop.api;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.shop.entity.Technician;
import org.nearslot.shop.mapper.TechnicianMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 技师评分聚合单测（FR-12）。
 *
 * 增量均值公式写错不会报错，只会让评分慢慢漂移，而评分直接影响"选哪个技师"——
 * 属于典型的"错了很久才发现"。这里把公式与边界逐条钉死。
 *
 * 断言方式（评审 MINOR）：按**列名**取值断言，而不是只看数值集合。
 * 否则 avg/count 写反照样通过。UpdateWrapper 生成的 SQL 形如
 * `rating_avg=#{ew.paramNameValuePairs.MPGENVAL1},rating_count=#{...MPGENVAL2}`，
 * 这里解析列名与参数键的对应关系。
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

    /** 执行一次聚合，返回"列名 -> 写入值"的映射。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> writtenColumns(Integer avg, Integer count, boolean apply, int score) {
        when(mapper.selectOne(any())).thenReturn(tech(avg, count));
        if (apply) updater.applyRating(2L, score); else updater.revertRating(2L, score);
        var captor = org.mockito.ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        var params = captor.getValue().getParamNameValuePairs();
        Map<String, Object> out = new LinkedHashMap<>();
        for (String part : sqlSet.split(",")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String col = part.substring(0, eq).trim();
            String rhs = part.substring(eq + 1).trim();
            int dot = rhs.lastIndexOf('.');
            if (dot < 0 || !rhs.endsWith("}")) continue;
            String key = rhs.substring(dot + 1, rhs.length() - 1);
            if (params.containsKey(key)) out.put(col, params.get(key));
        }
        return out;
    }

    private static int valueOf(Map<String, Object> m, String col) {
        Object v = m.get(col);
        if (!(v instanceof Number n))
            throw new AssertionError("未找到列 " + col + " 的写入值，实际：" + m);
        return n.intValue();
    }

    @Test
    @DisplayName("首评 5 分 -> 均分 500，条数 1")
    void firstRating() {
        var m = writtenColumns(500, 0, true, 5);
        assertEquals(500, valueOf(m, "rating_avg"), "首评 5 分应为 500，实际：" + m);
        assertEquals(1, valueOf(m, "rating_count"), "条数应为 1，实际：" + m);
    }

    @Test
    @DisplayName("已有 4.00 / 2 条，新增 5 分 -> (400*2+500)/3 = 433")
    void incrementalAverage() {
        var m = writtenColumns(400, 2, true, 5);
        assertEquals(433, valueOf(m, "rating_avg"), "应为 433，实际：" + m);
        assertEquals(3, valueOf(m, "rating_count"), "条数应为 3，实际：" + m);
    }

    @Test
    @DisplayName("首评 1 分 -> 均分 100（1.00）")
    void firstBadRating() {
        var m = writtenColumns(500, 0, true, 1);
        assertEquals(100, valueOf(m, "rating_avg"), "首评 1 分应为 100，实际：" + m);
    }

    @Test
    @DisplayName("撤销唯一一条 -> 0（未评级），不得回落 500 把差评技师抬成满分")
    void revertToEmpty() {
        var m = writtenColumns(100, 1, false, 1);
        assertEquals(0, valueOf(m, "rating_avg"), "撤到 0 条应为 0（未评级），实际：" + m);
        assertEquals(0, valueOf(m, "rating_count"), "条数应为 0，实际：" + m);
    }

    @Test
    @DisplayName("撤销一条 5 分（原 4.33 / 3 条）-> 回到 4.00 / 2 条")
    void revertRestoresAverage() {
        var m = writtenColumns(433, 3, false, 5);
        assertEquals(400, valueOf(m, "rating_avg"), "撤销后应回到 400，实际：" + m);
        assertEquals(2, valueOf(m, "rating_count"), "条数应为 2，实际：" + m);
    }

    @Test
    @DisplayName("评审 MAJOR-1：5,5,1 后撤回 1 分，501 必须被钳制回 500")
    void revertDoesNotDriftAboveMax() {
        // (367*3 - 100)/2 = 500.5 -> 501，超出评分区间，必须钳制为 500
        var m = writtenColumns(367, 3, false, 1);
        assertEquals(500, valueOf(m, "rating_avg"), "撤 1 分后应钳制在 500，实际：" + m);
        assertEquals(2, valueOf(m, "rating_count"), "条数应为 2，实际：" + m);
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
