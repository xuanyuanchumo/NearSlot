package org.nearslot.shop.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 技师。 */
@Data
@TableName("technician")
public class Technician {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long shopId;
    private String name;
    private Integer level;
}
