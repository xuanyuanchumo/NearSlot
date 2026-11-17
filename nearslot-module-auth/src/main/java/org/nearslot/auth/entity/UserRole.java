package org.nearslot.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用户角色与数据归属（多租户平台 P0 安全）。
 * role: CONSUMER / STAFF / MERCHANT / PLATFORM
 * shopId: 该用户可管理的店铺（MERCHANT/STAFF 必填，CONSUMER 为 null）。
 */
@Data
@TableName("user_role")
public class UserRole {
    @TableId(type = IdType.INPUT)
    private Long userId;
    /** CONSUMER / STAFF / MERCHANT / PLATFORM */
    private String role;
    /** 归属店铺 ID；平台管理员可为 null（表示全部）。 */
    private Long shopId;
}
