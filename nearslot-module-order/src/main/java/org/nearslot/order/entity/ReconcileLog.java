package org.nearslot.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 对账日志：logType(1BOOK/2RESTORE) × result(1SUCCESS/2FAIL)。 */
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
