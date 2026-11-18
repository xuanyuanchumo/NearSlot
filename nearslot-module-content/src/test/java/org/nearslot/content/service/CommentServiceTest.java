package org.nearslot.content.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nearslot.common.enums.BaseCode;
import org.nearslot.common.exception.BaseException;
import org.nearslot.content.entity.Blog;
import org.nearslot.content.entity.BlogComment;
import org.nearslot.content.mapper.BlogCommentMapper;
import org.nearslot.content.mapper.BlogMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 评论单测（FR-7）：内容校验、楼层归属、计数原子增减。 */
class CommentServiceTest {

    private BlogCommentMapper commentMapper;
    private BlogMapper blogMapper;
    private CommentService service;

    @BeforeEach
    void setUp() {
        commentMapper = mock(BlogCommentMapper.class);
        blogMapper = mock(BlogMapper.class);
        service = new CommentService(commentMapper, blogMapper);
    }

    private Blog blog() {
        Blog b = new Blog();
        b.setId(7001L);
        b.setUserId(2002L);
        b.setStatus(1);
        return b;
    }

    @Test
    @DisplayName("add: 内容为空 -> 40001")
    void addRejectsBlank() {
        BaseException ex = assertThrows(BaseException.class,
            () -> service.add(1001L, 7001L, null, "  "));
        assertEquals(BaseCode.BAD_REQUEST, ex.getCode());
    }

    @Test
    @DisplayName("add: 内容超长 -> 40001")
    void addRejectsTooLong() {
        String longText = "x".repeat(1025);
        BaseException ex = assertThrows(BaseException.class,
            () -> service.add(1001L, 7001L, null, longText));
        assertEquals(BaseCode.BAD_REQUEST, ex.getCode());
    }

    @Test
    @DisplayName("add: 笔记不存在 -> 40401")
    void addMissingBlog() {
        when(blogMapper.selectById(7001L)).thenReturn(null);
        BaseException ex = assertThrows(BaseException.class,
            () -> service.add(1001L, 7001L, null, "好"));
        assertEquals(BaseCode.NOT_FOUND, ex.getCode());
    }

    @Test
    @DisplayName("add: 正常评论 -> 落库 + comments 计数 +1")
    void addWorks() {
        when(blogMapper.selectById(7001L)).thenReturn(blog());

        Long id = service.add(1001L, 7001L, null, "同感");

        assertNotNull(id);
        verify(commentMapper).insert(argThat((BlogComment c) ->
            c.getBlogId().equals(7001L) && c.getUserId().equals(1001L) && c.getStatus() == 1));
        verify(blogMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("add: 回复他人笔记下的评论 -> 校验楼层归属")
    void addReplyChecksParent() {
        when(blogMapper.selectById(7001L)).thenReturn(blog());
        BlogComment parent = new BlogComment();
        parent.setId(8001L);
        parent.setBlogId(9999L);   // 属于别的笔记
        when(commentMapper.selectById(8001L)).thenReturn(parent);

        BaseException ex = assertThrows(BaseException.class,
            () -> service.add(1001L, 7001L, 8001L, "回复"));

        assertEquals(BaseCode.BAD_REQUEST, ex.getCode());
    }

    @Test
    @DisplayName("remove: 删他人评论 -> 403")
    void removeForeignRejected() {
        BlogComment c = new BlogComment();
        c.setId(8001L);
        c.setUserId(2002L);
        c.setBlogId(7001L);
        c.setStatus(1);
        when(commentMapper.selectById(8001L)).thenReturn(c);

        BaseException ex = assertThrows(BaseException.class,
            () -> service.remove(1001L, 8001L));

        assertEquals(BaseCode.FORBIDDEN, ex.getCode());
    }

    @Test
    @DisplayName("remove: 删自己评论 -> 逻辑删除 + 计数 -1")
    void removeOwn() {
        BlogComment c = new BlogComment();
        c.setId(8001L);
        c.setUserId(1001L);
        c.setBlogId(7001L);
        c.setStatus(1);
        when(commentMapper.selectById(8001L)).thenReturn(c);

        service.remove(1001L, 8001L);

        verify(commentMapper).update(isNull(), any());
        verify(blogMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("remove: 已删除 -> 幂等，不重复减计数")
    void removeIdempotent() {
        BlogComment c = new BlogComment();
        c.setId(8001L);
        c.setUserId(1001L);
        c.setBlogId(7001L);
        c.setStatus(2);
        when(commentMapper.selectById(8001L)).thenReturn(c);

        service.remove(1001L, 8001L);

        verify(commentMapper, never()).update(any(), any());
    }
}
