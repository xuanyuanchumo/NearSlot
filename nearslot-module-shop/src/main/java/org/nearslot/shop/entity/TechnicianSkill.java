package org.nearslot.shop.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 技师-服务项技能关联（多对多，FR-2.4）。 */
@Data
@TableName("technician_skill")
public class TechnicianSkill {
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long technicianId;
    private Long serviceItemId;
    /** 熟练度 1-5，用于同技能多技师排序。 */
    private Integer proficiency;
}
