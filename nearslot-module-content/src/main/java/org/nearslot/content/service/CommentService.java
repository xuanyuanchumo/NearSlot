package org.nearslot.content.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.common.page.PageResult;
import org.nearslot.content.entity.Blog;
import org.nearslot.content.entity.BlogComment;
import org.nearslot.content.mapper.BlogCommentMapper;
import org.nearslot.content.mapper.BlogMapper;
import org.nearslot.framework.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 笔记评论（FR-7）。comments 计数走冗余列原子增减（ADR-028）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    private final BlogCommentMapper commentMapper;
    private final BlogMapper blogMapper;

    /** 发表评论（parentId 非空即楼中楼回复）。 */
    @Transactional(rollbackFor = Exception.class)
    public Long add(Long userId, Long blogId, Long parentId, String content) {
        if (content == null || content.isBlank()) {
            throw new BaseException(BaseCode.BAD_REQUEST, "评论内容不能为空");
        }
        if (content.length() > 1024) {
            throw new BaseException(BaseCode.BAD_REQUEST, "评论过长（最多 1024 字）");
        }
        Blog blog = blogMapper.selectById(blogId);
        if (blog == null || blog.getStatus() == null || blog.getStatus() != 1) {
            throw new BaseException(BaseCode.NOT_FOUND, "笔记不存在");
        }
        if (parentId != null) {
            BlogComment parent = commentMapper.selectById(parentId);
            if (parent == null || !parent.getBlogId().equals(blogId)) {
                throw new BaseException(BaseCode.BAD_REQUEST, "回复的评论不属于该笔记");
            }
        }
        BlogComment c = new BlogComment();
        c.setId(IdGenerator.nextId());
        c.setBlogId(blogId);
        c.setUserId(userId);
        c.setParentId(parentId);
        c.setContent(content);
        c.setStatus(1);
        commentMapper.insert(c);

        blogMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Blog>()
                .eq("id", blogId)
                .setSql("comments = comments + 1"));
        return c.getId();
    }

    /** 删除评论（仅作者本人，逻辑删除保留楼层）。 */
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long userId, Long commentId) {
        BlogComment c = commentMapper.selectById(commentId);
        if (c == null) throw new BaseException(BaseCode.NOT_FOUND, "评论不存在");
        if (!c.getUserId().equals(userId)) {
            throw new BaseException(BaseCode.FORBIDDEN, "只能删除自己的评论");
        }
        if (c.getStatus() != null && c.getStatus() == 2) return;  // 幂等
        commentMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BlogComment>()
                .eq("id", commentId).set("status", 2));
        blogMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Blog>()
                .eq("id", c.getBlogId())
                .setSql("comments = GREATEST(comments - 1, 0)"));
    }

    /** 笔记本的评论列表（分页，只返回正常状态）。 */
    public PageResult<BlogComment> list(Long blogId, long page, long size) {
        var q = com.baomidou.mybatisplus.core.toolkit.Wrappers.<BlogComment>lambdaQuery()
            .eq(BlogComment::getBlogId, blogId)
            .eq(BlogComment::getStatus, 1)
            .orderByAsc(BlogComment::getCreatedAt);
        var p = commentMapper.selectPage(
            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size), q);
        return PageResult.ofPage(
            p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize(), p.getPages());
    }
}
