package org.nearslot.appointment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.framework.cache.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 每日热门技师榜（FR-4.3）。
 *
 * 设计（research/02 hmdp-plus 同款）：
 *   - ZSet：key = nearslot:tech:rank:{shopId}:{yyyyMMdd}，member = technicianId，score = 成交单数；
 *   - 每次支付成功（真实履约）ZINCRBY +1，而非抢约时累加（避免取消/超时造成虚高）；
 *   - TTL 90 天（hmdp-plus 实证：Top 榜 90d）。
 *
 * 返回 Top N 时把 member 从字符串转回 Long 并附带 score，前端展示"技师/单数"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TechRankService {

    /** 榜单 TTL 由 `nearslot.appointment.tech-rank-ttl-days` 决定。 */

    private final StringRedisTemplate redis;
    /** 榜单 TTL 外置到 `nearslot.appointment.tech-rank-ttl-days`。 */
    private final org.nearslot.appointment.config.AppointmentProperties props;

    /** 支付成功后调用：为技师增加 1 次有效成交。 */
    public void recordDeal(Long shopId, Long technicianId, LocalDate date) {
        if (shopId == null || technicianId == null || date == null) return;
        String key = rankKey(shopId, date);
        redis.opsForZSet().incrementScore(key, String.valueOf(technicianId), 1.0);
        redis.expire(key, props.getTechRankTtlDays(), TimeUnit.DAYS);
        log.debug("技师榜 +1 shopId={} techId={} date={}", shopId, technicianId, date);
    }

    /** 查询某日 Top N（按成交数倒序）。 */
    public List<Map<String, Object>> top(Long shopId, LocalDate date, int limit) {
        String key = rankKey(shopId, date);
        int n = Math.max(1, Math.min(limit, 100));
        Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> tuples =
            redis.opsForZSet().reverseRangeWithScores(key, 0, n - 1L);
        List<Map<String, Object>> out = new ArrayList<>();
        if (tuples == null) return out;
        int rank = 1;
        for (var t : tuples) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("rank", rank++);
            row.put("serviceStaffId", t.getValue());
            row.put("deals", t.getScore() == null ? 0 : t.getScore().intValue());
            out.add(row);
        }
        return out;
    }

    private String rankKey(Long shopId, LocalDate date) {
        return RedisConstants.TECH_RANK + shopId + ":" + date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
    }
}
