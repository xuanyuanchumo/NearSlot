package org.nearslot.common.page;

import java.io.Serializable;
import java.util.List;

/**
 * 统一分页响应（api/design.md §6）。
 *
 * **为什么放在 common 且不引用 MyBatis-Plus**：
 * 前端所有列表接口都按同一形状解析；若各模块自己拼 Map
 * （此前 ShopService 就是 Map.of(items,total,page,size)），
 * OpenAPI 契约里就没有可复用 schema，前端类型只能手写。
 * 而 common 必须**零依赖**（C-ARCH-01），所以这里只定义纯数据结构。
 * 需要 DB 的模块自己用 record 构造即可（一行）：
 * {@code new PageResult<>(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize(), p.getPages())}。
 * 刻意不提供 MP 转换工具类——那会把 MP 类型渗进 framework/common，破坏分层。
 *
 * 字段语义与 MP Page 对齐：records→items、current→page、size→size、pages→pages。
 */
public record PageResult<T>(
    /** 当前页数据。 */
    List<T> items,
    /** 满足条件的总记录数。 */
    long total,
    /** 当前页码（从 1 开始）。 */
    long page,
    /** 每页条数。 */
    long size,
    /** 总页数。 */
    long pages
) implements Serializable {

    /**
     * 直接由分页元数据构造（调用方从 MyBatis-Plus 的 Page 取值后传入）。
     *
     * 为什么不在这里接收 MP 的 Page：common 必须零依赖（C-ARCH-01），
     * 引入 MP 类型会让 common 反向依赖 ORM——这是架构铁律禁止的。
     */
    public static <T> PageResult<T> ofPage(List<T> items, long total, long page, long size, long pages) {
        return new PageResult<>(items, total, page, size, pages);
    }

    /** 空页（参数非法时也返回结构一致的结果，避免前端分别处理）。 */
    public static <T> PageResult<T> empty(long page, long size) {
        return new PageResult<>(List.of(), 0L, page, size, 0L);
    }

    /** 是否还有下一页（前端"加载更多"用）。 */
    public boolean hasMore() {
        return page < pages;
    }
}
