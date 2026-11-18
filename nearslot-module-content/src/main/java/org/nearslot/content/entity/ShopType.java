package org.nearslot.content.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.nearslot.framework.mybatis.BaseEntity;

/** 店铺分类（FR-10）。此前 NearSlot 只有 shop.type_id 字段，没有分类管理。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("shop_type")
public class ShopType extends BaseEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private String name;
    private String icon;
    private Integer sort;
}
