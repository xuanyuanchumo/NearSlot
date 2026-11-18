package org.nearslot.appointment.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 预约域策略配置（`nearslot.appointment.*`）。
 *
 * 这些值直接决定**资金与库存安全**，必须可按门店/业态调整：
 * · Hold 窗口过短 -> 用户支付来不及，超卖回收频繁；
 * · Hold 窗口过长 -> 时段被长时间锁死，翻台率下降。
 * 取值应由支付 P99 与业务周转共同决定，写在代码里等于把运营参数冻结。
 */
@Data
@Component
@ConfigurationProperties(prefix = "nearslot.appointment")
public class AppointmentProperties {

    /** Hold 窗口（分钟）：必须大于支付 P99（research/08 §4）。 */
    private int holdMinutes = 10;

    /** 单次候补提升最多跳过多少个不合格候选（防死循环）。 */
    private int maxPromotionSkip = 20;

    /** 「我的预约」列表返回上限。 */
    private int myOrdersLimit = 50;

    /** 可约时段查询上限（单店单日）。 */
    private int slotQueryLimit = 200;

    /** 对账任务单批处理量。 */
    private int reconcileBatchSize = 30;

    /** 核销码长度（位数）。可按门店品牌偏好调整（如 8 位更易读、12 位更安全）。 */
    private int voucherCodeLength = 12;

    /** 核销码字母表：默认去掉 0/1/O/I/L 等易混字符。 */
    private String voucherCodeAlphabet = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

    /** 核销码签发冲突重试次数（唯一键碰撞时重试）。 */
    private int voucherIssueRetry = 3;

    /** Hold 超时扫描周期（毫秒）—— 越小回收越快，DB 压力越大。 */
    private long holdScanIntervalMs = 60_000L;

    /** 对账任务执行周期（毫秒）。 */
    private long reconcileIntervalMs = 5 * 60_000L;

    /** 对账在途窗口（毫秒）：此窗口内的订单不算异常，避免误报。 */
    private long reconcileInFlightWindowMs = 12_000L;

    /** 开抢提醒提前量（秒）。 */
    private long reminderAheadSeconds = 120L;

    /** 提醒去重窗口（秒）：同一用户同一时段在此窗口内只提醒一次。 */
    private long reminderDedupWindowSeconds = 300L;

    /** 候选/订阅状态 TTL（秒），默认 1 天。 */
    private long subscriptionTtlSeconds = 86400L;

    /** 技师日榜 TTL（天）—— hmdp-plus 实证 Top 榜取 90 天。 */
    private long techRankTtlDays = 90L;
}
