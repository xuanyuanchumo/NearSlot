package org.nearslot.content.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户积分账户（FR-9）。 */
@Data
@TableName("user_points")
public class UserPoints {

    @TableId(type = IdType.INPUT)
    private Long userId;
    private Integer balance;
    private Integer totalEarned;
    private LocalDateTime updatedAt;
}
