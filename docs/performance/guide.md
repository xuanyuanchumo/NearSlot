---
title: 性能设计规范
status: current
source_of_truth:
  - ../../docs/performance/guide.md
updated: 2026-11-16
---

# 性能设计规范

## 1. 多级缓存（四层，PickNear 实证结构）

| 层 | 组件 | 内容 | 过期 |
|---|---|---|---|
| L1a | Caffeine tokenValidVersionCache | Token 版本快拒 | 写后 5min |
| L1b | Caffeine userinfoCache | 用户档案 | 20min 过期 + 10min 异步刷新（刷新期返旧值） |
| L2 | Redis | 业务缓存（pipeline 批量） | TTL×Jitter(70%~100%) |
| L3 | MySQL | 兜底 + 回填 | - |

## 2. 缓存三防（缺一不算完成）

- 击穿：逻辑过期（RedisData.expireTime）+ 异步重建线程池 + 抢锁失败返旧值/null 降级。
- 穿透：空值缓存（空串 2min）+ BloomFilter（shop/voucher 式过滤器，expectedInsertions/falsePositiveRate 配置化，hmdp-plus 模式）。
- 雪崩：TTL 随机偏移 setWithJitter；热点预热标记。
- 一致性：先 DB 后删缓存；跨实例失效走 RabbitMQ 广播（fanout 交换机 + 每实例独占队列，替代 hmdp-plus 的 groupId 随机 uuid 每实例广播；hmdp-plus Invalidation 模式 + @ServiceLock 写锁）。

## 3. 热点链路专项

- ID 生成：本地预生成队列（Pipeline 批量 INCR 10000/批，PickNear 实测单笔 ↓50ms）。
- 抢约：Lua 原子预扣（余量+去重+trace 一次往返）→ RabbitMQ 多队列消费削峰 → DB 乐观锁落库；瞬时 DB 查询 ↓90% 为设计目标（PickNear 实证值）。
- 拦截器：Caffeine 快拒 + 异步批量回填（BatchLoadCache 2s 调度 + using/writing 双 Set），空接口 TPS 2800→5500 为基线参考。
- 排行/榜：ZSet incrementScore + 日键 TTL 90d，零点归档（hmdp-plus Top 买家模式）。

## 4. 连接与线程池

- Hikari maximum-pool-size 50 / minimum-idle 10（prod）；Redis lettuce pool 50/20/5。
- 业务线程池统一 ThreadPoolExecutor + 有界队列 + CallerRunsPolicy + NamedThreadFactory(UncaughtExceptionHandler) + @PreDestroy 优雅关停（hmdp-plus 模式）；拒绝时必须可观测（counter）。
- Tomcat prod：max-connections 1000 / accept-count 1000 / threads.max 500（PickNear prod 基线）。

## 5. 压测方法（PickNear 教训：不记录条件的数字不可信）

- 每次压测必须记录：机器规格 / 并发线程 / 时长 / 数据量 / Redis 与 DB 拓扑 / JDK 版本，结果入 docs/performance/benchmarks.md。
- 工具：wrk/wrk2 + JMeter（含 SSE 场景）；基线指标：空接口 TPS、抢约 TPS、P99 < 200ms、错误率 < 0.1%。
- 压测环境独立，禁止对 prod 直压；数据用影子库。

## 6. 性能回归红线

- PR 携带性能敏感变更（缓存键/Lua/SQL/线程池）必须在描述中附复测数据或声明无影响。
- Lua 单次执行 >200ms 告警；拦截器 >100ms 告警；订单主链 >500ms 告警（PickNear 阈值继承）。
