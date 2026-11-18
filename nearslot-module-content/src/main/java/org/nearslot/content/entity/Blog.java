package org.nearslot.content.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.nearslot.framework.mybatis.BaseEntity;

/**
 * 探店笔记（FR-7，ADR-026 履约驱动）。
 *
 * 与参考项目的关键差异：**必须关联已完成订单** order_id。
 * hmdp-plus/PickNear 允许任意用户无订单发帖，内容与消费完全脱钩，
 * 这是本地生活平台最需要防的水军问题；NearSlot 有真实核销链路（订单 DONE），
 * 用它作为内容可信锚点。
 *
 * liked / comments 是冗余计数列（ADR-028）：列表页排序与展示不能走 COUNT。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("blog")
public class Blog extends BaseEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long userId;
    private Long shopId;
    /** 履约订单号：ADR-026 要求发布时校验该订单已完成。 */
    private Long orderId;
    private String title;
    private String content;
    /** 逗号分隔的图片 URL。 */
    private String images;
    /** 点赞数（冗余列）。 */
    private Integer liked;
    /** 评论数（冗余列）。 */
    private Integer comments;
    /** 1 已发布 / 2 隐藏。 */
    private Integer status;
}
