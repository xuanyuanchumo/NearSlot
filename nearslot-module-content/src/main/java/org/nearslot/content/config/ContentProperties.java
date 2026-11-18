package org.nearslot.content.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 内容/社交/积分策略配置（`nearslot.content.*`）。
 *
 * **为什么外置而非写常量**（2026-11-18 治理项）：
 * 这些值都是**运营可调的业务策略**，不是技术不变式。
 * 写死在代码里意味着"每次调参都要改代码 + 重新发版 + 回归测试"，
 * 而它们的正确取值只能由真实数据决定（例如大 V 阈值要按粉丝分布定）。
 *
 * 与之相对，Redis 键前缀、MQ 交换机名、订单状态码**保持常量**：
 * 它们是命名规范/协议的一部分，可配置化只会让运维排查变难。
 */
@Data
@Component
@ConfigurationProperties(prefix = "nearslot.content")
public class ContentProperties {

    /** 大 V 阈值（ADR-027）：粉丝数超过此值的作者不写扩散，走读取时合并。 */
    private int bigVThreshold = 1000;

    /** 读 Feed 时最多合并多少个大 V（防读放大失控）。 */
    private int maxBigVMerge = 20;

    /** 合并大 V 时每个作者取最新几篇。 */
    private int feedBlogsPerAuthor = 5;

    /** Feed 单次最多扫描的收件箱条数上限（防 ZSet 巨大时一次拉太多）。 */
    private int feedMaxFetch = 200;

    /** 关注/粉丝/点赞关系缓存的 TTL（小时）。 */
    private int relationCacheHours = 1;

    /** 核销码长度（位数）。 */
    private int voucherCodeLength = 12;

    /** 核销码字母表：默认去掉 0/1/O/I/L 等易混字符，避免人工抄码出错。 */
    private String voucherCodeAlphabet = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

    /** 签到基础积分。 */
    private int signBasePoints = 1;

    /** 连续签到满 N 天的额外奖励积分（0 = 关闭该档奖励）。 */
    private int signWeekBonus = 5;

    /** 周奖励所需连续天数。 */
    private int signWeekDays = 7;

    /** 连续签到满 N 天的月度奖励积分。 */
    private int signMonthBonus = 20;

    /** 月奖励所需连续天数。 */
    private int signMonthDays = 30;

    /** 连续签到回溯天数上限（跨月需换 BitMap key）。 */
    private int signContinuousLookbackDays = 60;

    /** 签到 BitMap 的 TTL（秒），默认 30 天。 */
    private long signBitmapTtlSeconds = 2592000L;
}
