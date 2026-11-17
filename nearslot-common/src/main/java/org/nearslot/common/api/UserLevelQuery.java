package org.nearslot.common.api;

/**
 * 用户等级查询的跨模块契约（依赖铁律 C-ARCH-01）。
 *
 * 为何放 common：appointment 需要校验会员等级，但**不能直接依赖 auth 模块**。
 * 此处只定义接口，由 auth 模块实现、Spring 注入，实现跨模块解耦。
 */
public interface UserLevelQuery {

    /** 查询用户会员等级；用户不存在返回 0。 */
    int levelOf(Long userId);
}
