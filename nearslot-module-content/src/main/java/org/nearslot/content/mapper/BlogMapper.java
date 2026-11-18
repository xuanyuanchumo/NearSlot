package org.nearslot.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.nearslot.content.entity.Blog;

/** Blog Mapper。 */
@Mapper
public interface BlogMapper extends BaseMapper<Blog> {
}
