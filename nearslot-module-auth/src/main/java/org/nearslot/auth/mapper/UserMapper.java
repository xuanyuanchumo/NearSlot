package org.nearslot.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.nearslot.auth.entity.User;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
