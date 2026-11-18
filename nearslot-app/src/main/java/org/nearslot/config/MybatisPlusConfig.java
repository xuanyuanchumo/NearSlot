package org.nearslot.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 基础设施（2026-11-17 三补）。
 *
 * **为什么必须有这一层**：
 * 此前全仓只有 {@code BaseMapper} 的裸继承，以下四项能力**一个都没启用**，
 * 导致业务代码被迫用 `.last("LIMIT 20")` 手拼 SQL：
 *
 * 1. **分页插件**（PaginationInnerInterceptor）——没有它，{@code selectPage} 返回全表；
 *    也正因缺失，代码里出现 5 处 {@code .last("LIMIT " + n)} 字符串拼接。
 *    拼接 limit 既无法参与 SQL 解析、又给注入口子（虽当前入参有钳制，但不该依赖）。
 * 2. **乐观锁插件**（OptimisticLockerInnerInterceptor）——{@code time_slot.version}、
 *    {@code time_slot_stock.version} 两个字段建了却从未生效，并发发布/关闭时段没有 CAS 保护。
 * 3. **防全表更新删除**（BlockAttackInnerInterceptor）——防止误写 {@code update(null)} 打穿全表。
 * 4. **自动填充**（MetaObjectHandler）——{@code created_at}/{@code updated_at} 依赖 DDL 默认值，
 *    应用侧 update 时 {@code updated_at} 由 MySQL ON UPDATE 兜底，但 insert 走实体时字段为 null，
 *    与 schema.md §1「每表必带，应用侧填充」的约定不一致。
 *
 * 顺序要求（MP 官方）：分页插件**必须最后**添加，否则其他插件改写的 SQL 不会被分页处理。
 */
@Configuration
@RequiredArgsConstructor
public class MybatisPlusConfig {

    /** 分页限流参数外置到 `nearslot.mybatis.*`（随 DB 规格调整，不写死 500）。 */
    private final MybatisProperties props;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 乐观锁：实体上标 @Version 的字段参与 CAS
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        // 防全表 update/delete（生产事故高发点）
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        // 分页：必须最后加
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页上限来自配置：防客户端传 size=100000 拖垮 DB（api/design.md §6）
        pagination.setMaxLimit(props.getMaxLimit());
        // 溢出总页数后不回到首页，直接返回空（避免"翻到第 N 页又跳回第 1 页"）
        pagination.setOverflow(props.isOverflow());
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    /** 公共字段自动填充（与 docs/database/schema.md §1 对齐）。 */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now();
                // strictInsertFill 只在字段存在且为 null 时填充，不会覆盖显式赋值
                strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
                strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
