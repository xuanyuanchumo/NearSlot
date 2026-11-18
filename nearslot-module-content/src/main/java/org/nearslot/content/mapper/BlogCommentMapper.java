package org.nearslot.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.nearslot.content.entity.BlogComment;

/** BlogComment Mapper。 */
@Mapper
public interface BlogCommentMapper extends BaseMapper<BlogComment> {
}
