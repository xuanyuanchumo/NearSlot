package org.nearslot.appointment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.nearslot.framework.mybatis.BaseEntity;

import java.time.LocalDateTime;

/**
 * 履约核销码（FR-6.1）。
 *
 * 一单一码，支付成功时签发；商家在「待核销」列表里按码核销，
 * 订单随之从 CONFIRMED 迁到 DONE（服务已完成）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("appointment_voucher")
public class AppointmentVoucher extends BaseEntity {

    /** 待核销。 */
    public static final int STATUS_UNUSED = 1;
    /** 已核销。 */
    public static final int STATUS_REDEEMED = 2;
    /** 已作废（订单取消/退款时置位，防止继续核销）。 */
    public static final int STATUS_VOID = 3;

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long orderId;
    private Long slotId;
    private Long shopId;
    private Long userId;
    /** 核销码：随机串，不放用户 ID 等可枚举信息（防遍历猜码）。 */
    private String code;
    /** 1 待核销 / 2 已核销 / 3 已作废。 */
    private Integer status;
    /** 核销人（店员或平台运营）。 */
    private Long redeemedBy;
    private LocalDateTime redeemedAt;
}
