package org.nearslot.content.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.nearslot.framework.mybatis.BaseEntity;

import java.time.LocalDateTime;

/**
 * 服务评价（FR-12，履约驱动）。
 *
 * 与笔记（FR-7）的区别：笔记是"体验分享"，评价是"打分"，后者直接驱动
 * technician.rating_avg —— 因此**一单一评**（uk_review_order）且必须能反查订单，
 * 不能在应用层只靠"先查再插"判断（并发下会双写）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("appointment_review")
public class AppointmentReview extends BaseEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    /** 履约订单（唯一）。 */
    private Long orderId;
    private Long userId;
    private Long shopId;
    /** 被评技师（餐品预约可为空）。 */
    private Long technicianId;
    private Long serviceItemId;
    /** 评分 1..5。 */
    private Integer rating;
    private String content;
    /** 逗号分隔图片 URL。 */
    private String images;
    /** 1=匿名展示（列表中隐藏 userId）。 */
    private Integer anonymous;
    private String merchantReply;
    private LocalDateTime replyAt;
    /** 1 正常 / 2 隐藏。 */
    private Integer status;
}
