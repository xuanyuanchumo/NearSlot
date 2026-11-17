---
title: 高并发内核深读（hmdp-plus 秒杀 V2/对账闭环/框架五件套源码）
status: current
source_of_truth:
  - ../../docs/research/07-concurrency-core-research.md
updated: 2026-11-16
---

# 研究报告 07：hmdp-plus 高并发内核深读（第二轮）

> 第一轮（02 报告）覆盖模块划分与框架清单；本报告进入方法级源码，拆解秒杀 V2 全链、
> Lua 审计日志、对账三态闭环、分片策略与框架五件套内部机制，作为 NearSlot 抢约/候补内核的直接蓝本。

## 1. 秒杀 V2 主链（VoucherOrderServiceImpl 888 行，方法级拆解）

```
doSeckillVoucherV2(voucherId)                          // V1 经典版保留对照（stream.orders 阻塞队列路径）
  ① seckillVoucherService.queryByVoucherId(voucherId)  // @ServiceLock(Read) + 本地缓存→Redis→BloomFilter→DB
  ② seckillVoucherService.loadVoucherStock(voucherId)  // 预热库存键（@ServiceLock(Read) 防击穿）
  ③ verifyUserLevel(model, userId)                     // 仅校验 allowedLevels CSV + minLevel（allowedCities 未参与实际校验）
  ④ orderId = nextId(); traceId = nextId()             // 同一生成器分别取号（非同一 ID）
  ⑤ seckillVoucherOperate.execute(keys, args)          // Lua 原子扣减（§2）
  ⑥ code != SUCCESS → BaseCode.getRc(code) 抛业务异常  // Lua 返回码枚举映射
  ⑦ seckillVoucherProducer.sendPayload(prefix+"-"+SECKILL_VOUCHER_TOPIC,
        SeckillVoucherMessage(userId, voucherId, orderId, traceId, beforeQty, deductQty, afterQty, autoIssue=false))
  ⑧ Result.ok(orderId)                                 // 立即返回订单号，前端轮询确认（前端侧 800ms/11s）
```

**消费侧 createVoucherOrderV2（幂等落库）**：
```
@RepeatExecuteLimit(name=SECKILL_VOUCHER_ORDER, keys={"#message.uuid"})  // 框架幂等注解（§5.2）
@Transactional(rollbackFor = Exception.class)
  → lambdaQuery 订单已存在（voucherId+userId+NORMAL）→ 抛 VOUCHER_ORDER_EXIST
  → update stock = stock - 1 where stock > 0            // DB 侧乐观锁兜底（Redis 已扣，此处防负库存）
  → save(VoucherOrder)（id 用消息携带的 orderId，保证幂等主键）
  → save(VoucherOrderRouter(orderId, userId, voucherId)) // 订单路由表：跨分片定位订单
  → redisCache.set(DB_SECKILL_ORDER_KEY:{orderId}, order, 60s) // 轮询窗口缓存
  → saveReconcileLog(DEDUCT, SUCCESS, "order created", message) // 对账日志
```
- **getSeckillVoucherOrder**（前端轮询接口）：Redis 60s 缓存 → VoucherOrderRouter 回查 → null。
  路由表的价值：订单按分片规则落库，但查询只用 orderId 即可定位。

## 2. Lua 原子层（审计日志进 Lua 的设计）

### 2.1 seckillVoucher.lua（60 行）
- KEYS：`stockKey / seckillUserKey / traceLogKey`；ARGV：voucherId、userId、beginTime、endTime、status、orderId、traceId、logType、ttlSeconds。
- **时间判断用 redis.call('TIME')**（Redis 服务器时钟，不信任应用时钟）：
  未开始→10002、已结束→10003；状态 2→10011、3→10012；无库存键→10004、库存≤0→10005；SISMEMBER 重复→10006。
- 原子动作：`incrby stockKey -1` + `sadd seckillUserKey userId`。
- **审计日志**：cjson.encode({logType, ts, orderId, traceId, userId, voucherId, beforeQty, changeQty, afterQty})
  → `hset traceLogKey traceId logEntry`，TTL = 距结束秒数+1 天。**扣减事实与业务动作同事务写入**，对账以此为准。
- 返回 JSON 字符串（code/beforeQty/deductQty/afterQty），Java 侧 SeckillVoucherDomain 反序列化。

### 2.2 seckillVoucherRollBack.lua（37 行）
- 读 stock（无→10004）→ `del stockKey`（**回滚即删键，等待下次 loadVoucherStock 重载**，避免回补值漂移）；
- operate==1 时 `srem` 移除一人一单标记；同样写 trace 日志（logType=RESTORE）。

## 3. 消费闭环（SeckillVoucherConsumer 257 行）

- 手动 ACK（Acknowledgment）+ @KafkaListener topic 前缀 Spring 注入（`SPRING_INJECT_PREFIX_DISTINCTION_NAME`，多环境隔离 topic）。
- **延迟门**：消息延迟 > MESSAGE_DELAY_TIME → 丢弃订单消息 + rollbackRedisVoucherData + saveReconcileLog(RESTORE,"message delayed ... rollback redis")；
  且"保存对账日志失败"单独 try/catch（**日志 best-effort，不反向阻断主流程**）。
- afterConsumeSuccess：清理订阅 ZSET 成员 → autoIssue==true 发自动发券通知 → 店铺 Top 买家统计（失败仅 warn 忽略）。
- afterConsumeFailure：HmdpFrameException 特定码 → rollbackRedisVoucherData + saveReconcileLog(RESTORE, "consume failed")。
- 未捕获异常线程工厂：NamedThreadFactory 命名 + UncaughtExceptionHandler 落日志。

## 4. 对账三态闭环（ReconciliationTaskServiceImpl 233 行）

```
reconciliationTaskExecute()
  → 遍历在售秒杀券 → loadPendingOrders(voucherId)
  → 每单 loadReconcileLogs(orderId)：dbLogCount == 1 或 2 且无缺失 → markConsistent
  → redisDeductTraceWithoutDbOrder：Redis trace 有 DEDUCT 但 DB 无订单
       ├ traceTs 距今 < MESSAGE_DELAY_TIME+2000ms → 跳过（消息仍在途，防误杀）
       └ 超时且未回滚 → delRedisStock（@ServiceLock(Write) 写锁串行）→ 回补 Redis
  → backfillMissingTraceLogs：DB 对账日志反哺重建 Redis trace（currentTtl<=0 时重新计算）
  → markOrderStatus(orderId, status)（@Transactional）
```
- 三态来源：Redis trace（§2 Lua）+ DB 对账日志（§1 消费侧）+ DB 订单，三方互证。
- **"在途保护窗口"（延迟阈值+2s）**是对账防误杀的关键设计——NearSlot 候补对账直接继承。

## 5. 框架五件套内部机制（源码级）

### 5.1 @ServiceLock（19 类）
- 注解 lockType（Fair/Read/Write/Reentrant）+ keys SpEL；ServiceLockAspect → ServiceLockFactory.getLock(type) →
  RedissonFairLocker/ReadLocker/WriteLocker/ReentrantLocker；LockTimeOutHandler 策略化超时；LocalLockCache 本地锁缓存减少 Redis 压力。
- 本报告实证：同一把 `UPDATE_SECKILL_VOUCHER_STOCK_LOCK` 被 loadVoucherStock(Read) 与 delRedisStock(Write) 共用——**读写锁分离让预热与回滚互斥**。

### 5.2 @RepeatExecuteLimit（7 类，幂等注解）
RepeatExecuteLimitAspect @Order(-11) 双检查模式：
```
① Redis flag 检查（SUCCESS_FLAG 已存在 → 抛 HmdpFrameException(message)）
② 本地 ReentrantLock tryLock（并发请求快速失败）
③ Redisson Fair 锁 tryLock(0)
④ 双重检查 flag（锁内再查一次）
⑤ proceed() 成功后 set flag（durationTime 秒 TTL）
```
幂等键由 LockInfoHandleFactory 按 SpEL 解析（本例 `#message.uuid`）。

### 5.3 MQ 模板（AbstractProducerHandler/AbstractConsumerHandler）
- 生产：sendMqMessage/sendPayload/sendRecord 三入口统一 Assert + whenComplete → afterSendSuccess/afterSendFailure 钩子；headers byte[]→String。
- 消费：consumeRaw → convert(MessageExtend<T>) → **beforeConsume（返回 false 跳过）→ doConsume（抽象）→ afterConsumeSuccess/afterConsumeFailure**；
  全部业务消费者继承模板，延迟门/回滚/对账钩子落在 after*。

### 5.4 令牌桶（tokenBucket.lua 62 行）
- **IP+User 双维度**（KEYS 两个桶）：单桶超限返回 10007(IP)/10008(USER)。
- 连续 refill：`tokens = min(capacity, tokens + delta*maxAttempts/windowMillis)`，HGET/HSET 存 last_ms+tokens；
- `clampDelta` 把追赶补币限制在 2×window 内（防时钟跳变刷币）；
- TTL = 2×window + rand(window/10) **随机抖动防雪崩过期**。

### 5.5 ID 生成（SnowflakeIdGenerator 167 行）
- 标准 5+5+12 位；BASIS_TIME=1288834974657（Twitter epoch）。
- workerId/datacenterId 三种来源：显式配置 > **MAC 地址推导 datacenterId（MAC 后两字节 >>6 取模）** > datacenterId+PID hash 推导 workerId。
- RedisIdWorker（core 内，经典版）：`timestamp << 32 | icr:{prefix}:{yyyy:MM:dd} 自增`，按天分键防溢出。

## 6. 分片策略（shardingsphere.yaml 184 行）

- 2 数据源 ds_0/ds_1（Hikari min-idle 30 / max 100 / leak-detection 30s）。
- 8 张分片表 ×2 库 ×2 表；broadcast 表 7 张（tb_blog/tb_blog_comments/tb_follow/tb_rollback_failure_log/tb_shop/tb_shop_type/tb_sign）。

| 表 | 库分片键 | 表分片键 | 设计意图 |
|---|---|---|---|
| tb_voucher_order | **user_id** | **voucher_id** | 用户维查单 + 券维聚合分开优化（异构路由） |
| tb_voucher_order_router | order_id | order_id | orderId 精确路由（订单号查单入口） |
| tb_voucher_reconcile_log | order_id | order_id | 与 router 同键，对账 join 不跨库 |
| tb_user / tb_user_info | id / user_id | 同键 | 用户域同键聚簇 |
| tb_user_phone | phone(hash) | phone | 手机号维度均衡 |
| tb_seckill_voucher / tb_voucher | voucher_id / id | 同键 | 券域同键 |

- **路由表模式的本质**：主表按业务维度分，查询入口维度单独立表冗余路由——NearSlot 预约单（按用户分片）+ 预约路由表（按预约号）照搬。

## 7. NearSlot 内核落地清单（来自本报告）

1. Lua 返回 JSON+枚举映射、TIME 服务器时钟、审计日志进 Lua（同事务事实）——三项全继承。
2. 回滚 Lua "del 键而非回补值"——NearSlot 库存键统一 del+reload，防漂移。
3. 消费延迟门+在途保护窗口（延迟阈值+2s）——对账防误杀继承。
4. @RepeatExecuteLimit 双检查幂等 + @ServiceLock 读写锁分离——注解化框架在 NearSlot frameworks 模块重写。
5. 订单路由表异构分片（主表 user_id / 路由表 order_id）——预约单照搬。
6. MQ 环境前缀（RabbitMQ exchange/routing key 前缀，替代源项目 Kafka topic 前缀）Spring 注入隔离多环境；对账日志 best-effort（单独 try/catch）不阻断主流程。
