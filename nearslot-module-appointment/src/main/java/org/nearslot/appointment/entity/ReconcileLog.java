package org.nearslot.appointment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 对账日志（logType:1 BOOK / 2 RESTORE；result:1 SUCCESS / 2 FAIL）。
 * 归属 appointment 域：与订单同分片键，便于库内 JOIN（schema.md §2）。
 */
@Data
@TableName("appointment_reconcile_log")
public class ReconcileLog {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long orderId;
    private Integer logType;
    private Integer result;
    private Integer beforeQty;
    private Integer changeQty;
    private Integer afterQty;
    private Long traceId;
    private String remark;
}
