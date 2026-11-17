package org.nearslot.appointment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 预约单（状态机：1待支付 2已确认 3已取消 4已完成 5已退款）。 */
@Data
@TableName("appointment_order")
public class AppointmentOrder {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long userId;
    private Long slotId;
    private Long shopId;
    private Long technicianId;
    private Integer status;
    private Integer amountCent;
    private LocalDateTime holdExpiresAt;
}
