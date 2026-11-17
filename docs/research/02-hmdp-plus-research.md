---
title: hmdp-plus 深度研究报告
status: current
source_of_truth:
  - ../../docs/research/02-hmdp-plus-research.md
project: hmdp-plus（黑马点评增强版）
updated: 2026-11-16
---

# hmdp-plus 深度研究报告

> 研究方法：8 模块 Maven 源码通读（240 个 java 文件）+ shardingsphere.yaml/application.yml 配置分析 + SQL 库表（2 库 × 23 表同构）验证。

## 1. 项目定位

- 解决"普通版黑马点评"的生产化痛点：**令牌限流、MQ 可靠性、一致性闭环、分库分表**。
- Spring Boot 3.5.4 + Java 17；Redisson 3.52.0、ShardingSphere 5.3.2、sa-token 1.43.0、knife4j 4.3.0、jasypt、alipay-sdk、Kafka（**源项目事实**：hmdp-plus 用 Kafka；NearSlot 选 RabbitMQ，见 ADR-014）。
- 前端 hmdp-vue3：Vue 3.5.13 + Element Plus 2.9.7（JS 非 TS）。

## 2. 模块化架构（最大工程亮点）

8 个 Maven 模块（org.javaup，revision + flatten-maven-plugin 统一版本）：

```
hmdp-common (20 类)          — 常量/枚举9/异常/ThreadLocal/SpringUtil
hmdp-core-service (116 类)   — 业务主模块 controller10/entity14/mapper14/service17+impl17
hmdp-sharding (1 文件)       — snakeyaml Representer 覆写（分片全靠 yaml 配置）
hmdp-redisson-framework      — service-lock(19)/redisson-common(12)/bloom-filter(5)/repeat-execute-limit(7)/service-delay-queue(12)
hmdp-redis-tool-framework    — redis-common/redis(键管理+缓存)/rate-limit(16:令牌桶+滑动窗口+SPI 扩展)
hmdp-id-generator-framework  — 雪花+workAndDataCenterId.lua
hmdp-mq-framework            — mq-common/consumer(AbstractConsumerHandler)/producer(AbstractProducerHandler)
hmdp-parameter (15 类)       — 独立 DTO 模块
```

框架模块全部是**注解 + AOP + 自动装配**形态，业务零侵入：

| 框架能力 | 注解 | 实现要点 |
|---|---|---|
| 方法级分布式锁 | @ServiceLock(lockType=Reentrant/Fair/Read/Write) | ServiceLockAspect + ManageLocker + LocalLockCache |
| 幂等防重 | @RepeatExecuteLimit(name, keys={"#spEL"}) | SpEL LockInfoHandle + Redisson |
| 布隆过滤器 | BloomFilterHandlerFactory.get(name) | 预期插入量+误判率可配（shop 50000/0.01） |
| 令牌桶/滑动窗口限流 | RedisRateLimitHandler | Lua：tokenBucket.lua/rateLimitSliding.lua + SPI（Scene/PenaltyPolicy/EventListener） |
| 延迟队列 | DelayQueueContext | Redisson 延迟队列 + 隔离区选择器 + InitHandler |

**NearSlot 启示**：把"锁/幂等/限流/布隆/延迟队列"沉淀为 framework 模块，业务模块只写注解——比 PickNear 的 utils 手工封装更可复用。

## 3. 秒杀一致性闭环（全链路）

### 3.1 下单主链（V2）

`doSeckillVoucherV2`：查券 → loadVoucherStock → verifyUserLevel（allowedLevels CSV + minLevel → UserInfo.level，不满足抛"当前会员级别不满足参与条件"）→ snowflakeIdGenerator.nextId() 两次分别取 orderId 与 traceId（非同源，源码实证 VoucherOrderServiceImpl.java:345-346）→ **seckillVoucher.lua**（3 KEYS：库存/用户/追踪日志；9 ARGV；redis TIME 判开售/结束；SISMEMBER 防重；DECR<0 回补；cjson 追踪日志 hset traceLogKey+expire）→ Kafka SeckillVoucherProducer（topic=prefix+"-"+SECKILL_VOUCHER_TOPIC）→ 返回 orderId。

Lua 返回结构化 JSON：`{"code":0,"beforeQty":N,"deductQty":N,"afterQty":N}`——库存前后变化可观测。

### 3.2 消费端（SeckillVoucherConsumer 257L）

- 线程池 max(2,CPU)/队列 1024*CPU/CallerRunsPolicy；@KafkaListener 手动 ack。
- beforeConsume 门：producerTime 延迟 >10s → **丢弃并回补 Redis**（rollbackRedisVoucherData 反转 beforeQty/afterQty）+ 对账日志 RESTORE/TIMEOUT。
- doConsume：@RepeatExecuteLimit(keys={"#message.uuid"}) + @Transactional → 重复订单查状态 → 乐观锁 `stock=stock-1 where stock>0` → save 订单（雪花 ID）→ save **VoucherOrderRouter（订单路由表）** → Redis 缓存订单 60s → 对账日志 DEDUCT/SUCCESS。
- afterConsumeSuccess（异步）：清订阅 ZSET 成员；autoIssue 补发通知；**Top 买家日榜**（shopId+yyyyMMdd SortedSet incrementScore，TTL 90 天）。
- afterConsumeFailure：回补 Redis（VOUCHER_ORDER_EXIST 例外不清用户 tag）+ 对账日志 RESTORE/FAIL。

### 3.3 一致性闭环（区别于 PickNear 的核心增量）

1. **Redis 追踪日志**：Lua 每次扣减写 trace 日志（logType/ts/orderId/traceId/userId/voucherId/beforeQty/changeQty/afterQty）。
2. **对账定时任务**（ReconciliationTaskServiceImpl）：遍历秒杀券 → 载入 reconciliationStatus=PENDING 订单 → 对比 Redis trace 日志 vs DB 订单 → Redis 有 trace 无 DB 单 → 删 Redis 库存 + 标记 ABNORMAL → AopContext 代理标记 CONSISTENT/ABNORMAL + 回填缺失 trace。
3. **回滚失败兜底**：RollbackFailureLog 表 + RollbackAlertService 告警。
4. **缓存失效广播**：SeckillVoucherInvalidationConsumer（groupId 带随机 uuid → 每实例广播消费）→ @ServiceLock(Write) 下 Caffeine invalidate + 删 3 个 Redis 键；失败打 meterRegistry 指标。
5. **本地缓存**：SeckillVoucherLocalCache Caffeine maximumSize(10000) + 自定义 Expiry（expireAfterCreate=距 endTime 秒数）。

### 3.4 营销配套

- 订阅通知：ZSet 余票回流（SECKILL_SUBSCRIBE_STATUS_TAG_KEY）；通知领取：Redisson 延迟队列开抢前 2 分钟预通知 + 去重控频（dedup.window.seconds=300）；AutoIssue 最早期订阅者自动补券（ZSet 逐页扫描跳过 exclude+已购）。
- 每日 Top 买家、延迟提醒 ConsumerDelayedVoucherReminder（389L，sms/app 开关、max.users、top.buyers.days=30 等配置驱动）。

## 4. 缓存与查询

- ShopServiceImpl queryByIdV4（V1 透传/V2 互斥/V3 逻辑过期均注释保留教学）：BloomFilter.contains → 空值缓存 → @ServiceLock(Reentrant) 锁内双查 → DB miss 写空值+抛 → 写缓存。
- CacheClient（183L）：passThrough/mutex/logicalExpire 三模式 + CACHE_REBUILD_EXECUTOR 线程池异步重建。
- update：先 DB 后删缓存；saveShop 雪花 ID + bloom add。
- GEO：GEOSEARCH BYLONLAT BYRADIUS 5000 + skip/limit + ORDER BY FIELD 回填 distance。

## 5. 分库分表

- shardingsphere.yaml（184 行）：8 张分片表，**库路由=MOD 分片算法，表路由=INLINE 表达式**：`tb_user_${id.intdiv(2) % 2}`、`tb_seckill_voucher_${voucher_id.intdiv(2) % 2}`、手机号 HASH_MOD(phone.hashCode())。
- 2 数据源（HikariCP×2）× 每表 _0/_1；1_create_database.sql 建库，hmdp_0/1.sql 同构 23 表。
- 分片表清单：user/user_info/user_phone/voucher/seckill_voucher/voucher_order/**voucher_order_router（订单路由表）**/voucher_reconcile_log。
- ID：SnowflakeIdGenerator + WorkAndDataCenterIdHandler（workId/dataCenterId lua 协调）。
- **NearSlot 启示**：订单路由表让"订单号→分片"免二次计算；对账日志表直接建在分片库内，避免跨库对账复杂度。

## 6. 配置要点（application.yml 83 行）

- datasource driver=ShardingSphereDriver url=jdbc:shardingsphere:classpath:shardingsphere.yaml。
- Kafka（**源项目事实**；NearSlot 用 RabbitMQ 等价参数组，ADR-014）：acks=all + retries 5 + enable.idempotence + max.in.flight 5；consumer 手动 ack manual_immediate + auto-offset-reset latest。
- jackson WRITE_NUMBERS_AS_STRINGS:true（**前端 Long 精度兜底**，与 json-bigint 双保险）。
- MP：type-aliases + log-impl + local-cache-scope statement（关一级缓存防脏读）。
- management endpoints：prometheus,health,info。

## 7. 基础业务（与 PickNear 同源对照）

- 登录：UUID token → Hash + expire（注释强调按秒设置避免 Redisson pExpire 递归问题）；RefreshTokenInterceptor(64L) 刷 TTL + LoginInterceptor(27L) 只查 UserHolder。
- 签到 BitMap BITFIELD；点赞 ZSet（时间戳 top5）；Feed 推模式滚动分页；关注 Set + intersect。
- 差异：无双 Token/无版本号/无防爆破（认证体系弱于 PickNear）。

## 8. 质量保障现状与不足

- 测试仅 3 个（ApplicationTests/NormalTest/RedissonTest）——**框架模块零测试、秒杀闭环零测试**，生产可靠性靠设计与运维兜底，是最大短板。
- 文档：README 很强（每个特性都有场景+伪代码），但无文档治理体系、无 frontmatter、无 CI 检查。
- 无 AI 能力；无全链路观测（仅 prometheus 指标 + 失败计数器）。
- 优点可直接复用的工程资产：框架五件套（锁/幂等/限流/布隆/延迟队列）、对账闭环数据模型（trace 日志+对账表+回滚失败表+路由表）、缓存失效广播模式、MQ 可靠性参数组（源项目为 Kafka，NearSlot 转 RabbitMQ 等价实现）。

## 9. 对 NearSlot 的吸收清单

1. Maven 多模块 + 框架沉淀（lock/idempotent/rate-limit/bloom/delay-queue 五件套注解化）。
2. 秒杀一致性闭环：trace 日志 + 对账任务 + 回滚失败表 + 订单路由表 + 缓存失效广播。
3. MQ 可靠性参数组（源项目 Kafka：acks=all/idempotence/手动 ack/延迟消息回补）→ NearSlot 用 RabbitMQ 等价实现：publisher-confirm/persistent/手动 ack/死信队列 + TTL 延迟（ADR-014）。
4. 分库分表配置模板（MOD 库 + INLINE 表 + HASH_MOD 手机号）+ 分片内对账。
5. 会员等级校验、订阅-回流-补发营销闭环、Top 买家日榜。
6. jackson 数字串化 + json-bigint 前端双保险。
7. README"场景+伪代码"文档风格（与 PickNear 治理体系结合后使用）。

—— 完 ——
