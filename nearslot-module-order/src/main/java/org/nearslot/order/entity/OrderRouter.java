package org.nearslot.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 订单路由表：order_id → (ds, table)，零二次计算（research/07 §6）。 */
@Data
@TableName("appointment_order_router")
public class OrderRouter {
    @TableId(type = IdType.INPUT)
    private Long orderId;
    private Long userId;
    private Long slotId;
    private String dsName;
    private String tblSuffix;
}
