package org.nearslot.content.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 积分流水（FR-9 / ADR-029）。
 *
 * 唯一键 (user_id, source, biz_id) 是幂等保证：
 * 同一签到日 / 同一订单不会重复入账。
 */
@Data
@TableName("points_log")
public class PointsLog {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long userId;
    /** 正=获得 负=消耗。 */
    private Integer changeAmount;
    private Integer balanceAfter;
    /** SIGN / ORDER / ADMIN / REFUND。 */
    private String source;
    private Long bizId;
    private String remark;
    private LocalDateTime createdAt;
}
