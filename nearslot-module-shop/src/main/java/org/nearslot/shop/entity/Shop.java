package org.nearslot.shop.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 服务商（含经纬度，供 GEOSEARCH）。 */
@Data
@TableName("shop")
public class Shop {
    @TableId(type = IdType.INPUT)
    private Long id;
    private String name;
    private Long typeId;
    private Double x;
    private Double y;
    private String address;
}
