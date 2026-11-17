package org.nearslot.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 手机号（HASH_MOD 分片，等值直查）。 */
@Data
@TableName("user_phone")
public class UserPhone {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String phone;
}
