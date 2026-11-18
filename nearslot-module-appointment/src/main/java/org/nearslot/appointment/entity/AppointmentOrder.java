package org.nearslot.appointment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.nearslot.framework.mybatis.BaseEntity;
import java.time.LocalDateTime;

/** 预约单（状态机：1待支付 2已确认 3已取消 4已完成 5已退款）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("appointment_order")
public class AppointmentOrder extends BaseEntity {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long userId;
    private Long slotId;
    private Long shopId;
    /** 履约执行者（ADR-021）。 */
    private Long serviceStaffId;
    /** 履约方式：DINE_IN/PICKUP/TAKEAWAY/DOOR_TO_DOOR（ADR-022）。 */
    private String fulfillmentMode;
    private Integer status;
    private Integer amountCent;

    // ------- 前端展示字段（评审 F-008：与 nearslot-web/types/api.ts Appointment 契约对齐）-------
    /** 预约时段开始时间（ISO，序列化由 JacksonConfig 带时区输出）。 */
    private java.time.LocalDateTime startsAt;
    /** IANA 时区（前端用 Intl.DateTimeFormat(tzId) 渲染墙钟时间）。 */
    private String tzId;
    /** 冗余展示字段，避免前端 N+1 查询。 */
    private String shopName;
    private String technicianName;
    /** 前端按 amountCents 读取（兼容旧字段名）。 */
    private Integer amountCents;
    private LocalDateTime holdExpiresAt;
    /** 对账状态：1 待对账 / 2 不一致 / 4 已一致（ReconcileStatus）。 */
    private Integer reconcileStatus;
}
