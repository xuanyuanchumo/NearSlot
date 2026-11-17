package org.nearslot.auth.service;

import lombok.RequiredArgsConstructor;
import org.nearslot.auth.entity.User;
import org.nearslot.auth.mapper.UserMapper;
import org.nearslot.common.api.UserLevelQuery;
import org.springframework.stereotype.Service;

/** 用户等级查询实现（暴露给 appointment 等模块，经 common 接口）。 */
@Service
@RequiredArgsConstructor
public class UserLevelQueryImpl implements UserLevelQuery {

    private final UserMapper userMapper;

    @Override
    public int levelOf(Long userId) {
        if (userId == null) return 0;
        User u = userMapper.selectById(userId);
        return u == null || u.getLevel() == null ? 0 : u.getLevel();
    }
}
