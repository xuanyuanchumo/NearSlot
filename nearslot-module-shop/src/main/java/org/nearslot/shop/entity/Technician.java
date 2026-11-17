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
    private String avatar;
    /** 等级：影响预约门槛与榜单。 */
    private Integer level;
    /** 在岗状态：1在岗 2请假 3离职。 */
    private Integer workStatus;
    /** 平均评分（放大100倍存整数，避免浮点）。 */
    private Integer ratingAvg;
    /** 评价数。 */
    private Integer ratingCount;
}
