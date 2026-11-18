package org.nearslot.content.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.nearslot.framework.mybatis.BaseEntity;

/** 笔记评论（FR-7），parentId 非空表示楼中楼回复。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("blog_comment")
public class BlogComment extends BaseEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long blogId;
    private Long userId;
    /** 回复的父评论 ID（null = 一级评论）。 */
    private Long parentId;
    private String content;
    /** 1 正常 / 2 删除（逻辑删除，保留楼层结构）。 */
    private Integer status;
}
