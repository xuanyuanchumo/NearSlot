package org.nearslot.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 用户（雪花主键，应用侧生成 ADR-007）。 */
@Data
@TableName("user")
public class User {
    @TableId(type = IdType.INPUT)
    private Long id;
    private String nickname;
    private String password;
    private Integer level;
    private Integer deleted;
}
