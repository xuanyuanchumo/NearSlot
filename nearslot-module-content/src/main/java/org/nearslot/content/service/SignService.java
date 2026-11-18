package org.nearslot.content.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.framework.cache.RedisConstants;
import org.nearslot.framework.id.IdGenerator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 签到与积分（FR-9 / ADR-029）。
 *
 * **本项目化适配**：
 * 1. **BitMap 存签到**（借鉴 PickNear `SignServiceImpl` 的 BitField 方案）：
 *    每人每月一个 key，第 N 位表示第 N 天是否签到，一个月的状态只占 4 字节。
 *    相比"每天一行签到记录"，存储量差 3 个数量级。
 * 2. **积分落流水**：签到发积分必须写 points_log，且用
 *    uk_points_once(user_id, source, biz_id) 保证**同一业务只发一次**（幂等）。
 *    两个参考项目的签到只记录"签没签"，没有可审计的积分账本。
 * 3. **连续签到奖励**：连续天数越多积分越高（本地生活标准留存手段），
 *    连续天数字段用 BitMap 反向统计，不额外建表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignService {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate redis;
    private final PointService pointService;
    /** 积分规则与签到窗口外置到 `nearslot.content.*`（运营可按季调）。 */
    private final org.nearslot.content.config.ContentProperties props;

    private String signKey(Long userId, LocalDate date) {
        return RedisConstants.PREFIX + "sign:" + userId + ":" + date.format(MONTH_FMT);
    }

    /**
     * 签到（幂等：当天重复签到返回已签到）。
     *
     * BitMap 用 Lua 保证 SETBIT 与"是否已签"判定原子，
     * 否则并发两次请求可能都判定未签到、发两倍积分。
     */
    @Transactional(rollbackFor = Exception.class)
    public SignResult sign(Long userId) {
        LocalDate today = LocalDate.now();
        int dayOfMonth = today.getDayOfMonth();
        String key = signKey(userId, today);

        // Lua：SETBIT 前先 GETBIT 判断，返回 1=新签到 0=已签到
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        // TTL 也走 ARGV：写死在脚本文本里就无法按环境调整（测试环境可能只需 1 天）
        script.setScriptText(
            "local cur = redis.call('GETBIT', KEYS[1], ARGV[1]) "
          + "if cur == 1 then return 0 end "
          + "redis.call('SETBIT', KEYS[1], ARGV[1], 1) "
          + "redis.call('EXPIRE', KEYS[1], ARGV[2]) "
          + "return 1");
        script.setResultType(Long.class);
        Long fresh = redis.execute(script, List.of(key),
            String.valueOf(dayOfMonth - 1),              // BitMap 下标从 0 开始
            String.valueOf(props.getSignBitmapTtlSeconds()));

        if (fresh == null || fresh != 1L) {
            log.info("重复签到 userId={} date={}", userId, today);
            return new SignResult(false, 0, 0, pointService.balanceOf(userId));
        }

        int continuous = continuousDays(userId, today);
        int points = props.getSignBasePoints()
            + (props.getSignWeekDays() > 0 && continuous % props.getSignWeekDays() == 0
                ? props.getSignWeekBonus() : 0)
            + (props.getSignMonthDays() > 0 && continuous % props.getSignMonthDays() == 0
                ? props.getSignMonthBonus() : 0);
        int balance = pointService.addPoints(userId, points, "SIGN",
            Long.valueOf(today.format(DAY_FMT).replace("-", "")),
            "签到第 " + dayOfMonth + " 天，连续 " + continuous + " 天");

        log.info("签到成功 userId={} continuous={} points={} balance={}",
            userId, continuous, points, balance);
        return new SignResult(true, continuous, points, balance);
    }

    /**
     * 统计连续签到天数（从今天往前数）。
     *
     * 为什么用 BitMap 反向读而不是建表：连续天数只是展示与奖励判定，
     * 每天一条记录属于过度建模（参考项目也正是这么做的）。
     */
    public int continuousDays(Long userId, LocalDate today) {
        // 最多回看天数由配置决定（跨月需换 BitMap key，故设上限防误配成极大值）
        int maxBack = props.getSignContinuousLookbackDays();
        int count = 0;
        LocalDate cursor = today;
        for (int i = 0; i < maxBack; i++) {
            String key = signKey(userId, cursor);
            Boolean signed = redis.opsForValue().getBit(key, cursor.getDayOfMonth() - 1);
            if (!Boolean.TRUE.equals(signed)) break;
            count++;
            cursor = cursor.minusDays(1);
        }
        return count;
    }

    /** 当月签到日历（返回已签到的日号列表，供前端渲染签到墙）。 */
    public List<Integer> monthSignedDays(Long userId, LocalDate month) {
        String key = signKey(userId, month);
        int days = month.lengthOfMonth();
        List<Integer> out = new java.util.ArrayList<>();
        for (int d = 1; d <= days; d++) {
            Boolean signed = redis.opsForValue().getBit(key, d - 1);
            if (Boolean.TRUE.equals(signed)) out.add(d);
        }
        return out;
    }

    /** 今日是否已签到。 */
    public boolean signedToday(Long userId) {
        LocalDate today = LocalDate.now();
        return Boolean.TRUE.equals(redis.opsForValue()
            .getBit(signKey(userId, today), today.getDayOfMonth() - 1));
    }

    /** 签到结果。 */
    public record SignResult(boolean fresh, int continuousDays, int points, int balance) {}
}
