package org.nearslot.appointment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.appointment.entity.AppointmentOrder;
import org.nearslot.appointment.mapper.AppointmentOrderMapper;
import org.nearslot.framework.cache.RedisConstants;
import org.nearslot.framework.delayqueue.RedissonDelayQueue;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 店铺准备提醒（ADR-022）——预约区别于外卖的核心能力。
 *
 * 用户预约履约时间 T，系统在 T - lead_time 前提醒店铺准备。
 * 复用 RedissonDelayQueue（与 Hold 超时、FR-3.4 开抢提醒同一套），零新增中间件。
 *
 * v1 只做 API + 日志（店铺端 UI 后续）；消息幂等：同一订单只提醒一次
 * （延迟消息本身一次性消费，重复消费由业务日志去重）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrepareReminderService {

    public static final String PREPARE_QUEUE = "nearslot:delay:prepare";

    private final RedissonDelayQueue delayQueue;
    private final AppointmentOrderMapper orderMapper;

    /**
     * 支付成功后安排准备提醒。
     *
     * @param orderId 预约单
     * @param performAt 履约时间（时段 startsAt）
     * @param leadTimeMin 准备提前量（分钟，来自履约项配置；<=0 不提醒）
     */
    public void schedule(Long orderId, LocalDateTime performAt, int leadTimeMin) {
        if (performAt == null || leadTimeMin <= 0) return;
        long delaySec = Duration.between(LocalDateTime.now(),
            performAt.minusMinutes(leadTimeMin)).getSeconds();
        if (delaySec <= 0) {
            // 已过提醒点：立即提醒（不丢弃）
            log.info("准备提醒立即触发 orderId={} lead={}min", orderId, leadTimeMin);
            fire(orderId);
            return;
        }
        delayQueue.offer(PREPARE_QUEUE, String.valueOf(orderId), delaySec);
        log.info("准备提醒已排期 orderId={} delay={}s（提前 {}min）", orderId, delaySec, leadTimeMin);
    }

    /** 启动消费者（随应用启动注册一次）。 */
    public void startConsumer() {
        delayQueue.startConsumer(PREPARE_QUEUE, payload -> {
            try {
                fire(Long.valueOf(String.valueOf(payload)));
            } catch (RuntimeException e) {
                log.error("准备提醒处理失败 payload={}", payload, e);
            }
        });
    }

    /** 执行提醒：v1 记日志（店铺端订阅通道后续接）。 */
    void fire(Long orderId) {
        AppointmentOrder o = orderMapper.selectById(orderId);
        if (o == null) return;
        log.info("【店铺准备提醒】orderId={} shopId={} staffId={} fulfillment={} 请及时准备",
            orderId, o.getShopId(), o.getServiceStaffId(), o.getFulfillmentMode());
    }
}
