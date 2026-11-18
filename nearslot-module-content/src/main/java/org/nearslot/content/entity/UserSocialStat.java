package org.nearslot.content.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户社交统计（ADR-028）。
 *
 * 为什么独立成表而不是加到 user：user 属于 auth 模块，
 * 社交统计属于 content 域，跨域写同一张表会造成模块边界模糊（C-ARCH-01）。
 */
@Data
@TableName("user_social_stat")
public class UserSocialStat {

    @TableId(type = IdType.INPUT)
    private Long userId;
    private Integer followingCount;
    private Integer followerCount;
    private Integer blogCount;
    /** 获赞总数。 */
    private Integer likedReceived;
    private LocalDateTime updatedAt;
}
