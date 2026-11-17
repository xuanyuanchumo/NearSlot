package org.nearslot.shop.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.nearslot.framework.mybatis.BaseEntity;

/** 服务商（含经纬度，供 GEOSEARCH）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("shop")
public class Shop extends BaseEntity {
    @TableId(type = IdType.INPUT)
    private Long id;
    private String name;
    private Long typeId;
    private Double x;
    private Double y;
    private String address;
}
