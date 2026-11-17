package org.nearslot.appointment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 时段定义（uq_slot_occupy 唯一约束是防重复预约最后防线，research/08 §2）。 */
@Data
@TableName("time_slot")
public class TimeSlot {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long shopId;
    private Long technicianId;
    private Long serviceItemId;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private String tzId;
    private Integer capacity;
    private Integer status;
    private Integer version;
    /** 预约该时段所需最低会员等级（0=无门槛）。 */
    private Integer minLevel;
}
