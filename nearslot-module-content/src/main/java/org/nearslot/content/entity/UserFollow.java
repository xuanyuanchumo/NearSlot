package org.nearslot.content.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 关注关系（FR-8 / ADR-028）。
 *
 * 关系表是事实源；Redis Set 只是读加速。
 * 冗余计数在 user_social_stat，禁止对关系表做 COUNT。
 */
@Data
@TableName("user_follow")
public class UserFollow {

    @TableId(type = IdType.INPUT)
    private Long id;
    /** 关注者。 */
    private Long userId;
    /** 被关注者。 */
    private Long followUserId;
    private LocalDateTime createdAt;
}
