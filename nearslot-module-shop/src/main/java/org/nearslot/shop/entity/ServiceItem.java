package org.nearslot.shop.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 服务项（FR-2.1 管理）。 */
@Data
@TableName("service_item")
public class ServiceItem {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long shopId;
    private String name;
    /** 服务时长（分钟），用于时段推荐计算。 */
    private Integer durationMin;
    /** 价格（分），避免浮点精度丢失。 */
    private Integer priceCent;
}
