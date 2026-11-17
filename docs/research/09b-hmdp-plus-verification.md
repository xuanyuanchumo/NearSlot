---
title: hmdp-plus 核验报告（CONFIRMED / CORRECTED / NEW）
status: current
source_of_truth:
  - ../../docs/research/09b-hmdp-plus-verification.md
updated: 2026-11-16
---

# hmdp-plus 核验报告（源码逐行复核）

> 方法：读取 `research/02,03,07` 后对 hmdp-plus 源码逐行核验。路径相对 `D:/Projects/TraeProjects/Studying`。性能数字为项目自述、需复测。

## 1. Waitlist / 订阅自动发放（CONFIRMED + NEW）

**CONFIRMED**：
- Redis 键：`SECKILL_SUBSCRIBE_ZSET_TAG_KEY`(ZSet member=userId score=加入时间)、`SECKILL_SUBSCRIBE_USER_TAG_KEY`(SET)、`SECKILL_SUBSCRIBE_STATUS_TAG_KEY`(HASH)——`RedisKeyManage.java:62-66`。
- subscribe()：已购校验 :276-279、加 SET :290-292、首次加入才 ZADD(score=currentTimeMillis) :295-301、status=SUBSCRIBED 且 SUCCESS 不降级 :303-308——`VoucherServiceImpl.java:252-309`。
- earliest 候选扫描 `findEarliestCandidate()`：pageCount=1、`rangeByScoreWithScoreForSortedSet(-inf,+inf,offset,1)`、跳过空元组/excludeUserId(:677-680)/已购 SISMEMBER(:681-685)——`VoucherOrderServiceImpl.java:649-688`。证实 research/03 §4 "逐页 rangeByScore + 跳过"。

**NEW（现有研究未强调）**：
- 自动发放有**两个触发器**：(a) 取消 `cancel()` 调 `autoIssueVoucherToEarliestSubscriber(voucherId, excludeUserId=取消者)`——`VoucherOrderServiceImpl.java:619-626`；(b) **库存增加**经 `SECKILL_ORDER_EXECUTOR` 异步触发——`VoucherServiceImpl.java:244-248`。
- `issueToCandidate()` 对候选**重跑 verifyUserLevel** 并重跑 Lua 扣减 + Producer(autoIssue=TRUE)——`VoucherOrderServiceImpl.java:690-724`。
- ZSet 成员清理在**消费端 afterConsumeSuccess**(:182-189)，再发通知(:190-197)，再 Top 买家 ZSet + 90d TTL(:198-218)——`SeckillVoucherConsumer.java:174-220`。通知去重 `setIfAbsent` 窗口 300s——`AutoIssueNotifyServiceImpl.java:24-68`。
- 延迟提醒：`delaySeconds = 距开售秒数 - max(reminderAheadSeconds,0)`，配置 `seckill.reminder.ahead.seconds:120`——`VoucherServiceImpl.java:92-93,481-507`；去重窗口 1800s、max 1000 用户——`ConsumerDelayedVoucherReminder.java:56-123,139-233`。

## 2. 对账三态闭环（CONFIRMED + NEW）

**CONFIRMED**——`ReconciliationTaskServiceImpl.java`(232 行)：
- loadPendingOrders：`createTime <= now-2min` 且 PENDING(:108-115)；空日志→ABNORMAL(:76-79)；dbLogCount==1||2 一致路径，anyMissing→delRedisStock(:85-88)；其他→ABNORMAL(:89-93)。
- `redisDeductTraceWithoutDbOrder`：仅 DEDUCT、无 DB 日志、age>=`MESSAGE_DELAY_TIME+2000ms`→delRedisStock(一次) + 删 trace + 移除用户 Set(:149-186)。**证实 research/07 §4 "在途保护窗口 延迟阈值+2s"**。
- `backfillMissingTraceLogs` 从 DB 日志重建 Redis trace，TTL<=0 重算(:188-217)；`markOrderStatus` @Transactional 同改订单+日志状态(:219-231)；`delRedisStock` 用 @ServiceLock(Write)(:102-106)。`MESSAGE_DELAY_TIME=10000L`——`SeckillVoucherConsumer.java:54`。

**NEW/修正**：状态枚举有**四态** PENDING/ABNORMAL/INCONSISTENT/CONSISTENT(`ReconciliationStatus.java:14-17`)，但 **INCONSISTENT 从未使用**（grep 仅声明）。"三态"实践准确，第四态是死码。

## 3. 框架五件套内部机制（CONFIRMED + NEW）

- @ServiceLock：`ServiceLockAspect.java` @Order(-10)，SpEL→factory.getLock(type)→tryLock/proceed/finally unlock(:29-93)；读写/公平/可重入 locker 在 impl/。
- @RepeatExecuteLimit：`RepeatExecuteLimitAspect.java` @Order(-11) 五步双检——flag 检查抛异常(:55-58)、本地 ReentrantLock(:59-63)、Redisson Fair tryLock(0)(:65-66)、双重检查(:69-72)、proceed+set flag(:73-81)。**与 research/07 §5.2 完全一致**。
- 令牌桶 `tokenBucket.lua`(62 行)：IP+User 双桶、码 10007/10008、clampDelta 限 2×window、TTL=2×window+rand(:13-61)。**证实 research/07 §5.4**。
- Bloom `BloomFilterHandler.java:13-39`：命名空间 `SpringUtil.getPrefixDistinctionName()`、默认 expectedInsertions=20000/falseProbability=0.01。
- 延迟队列 `DelayQueueContext.java:24-28`→`DelayQueueProduceCombine.java:21-32`(isolationRegionCount 分区轮询)→`DelayProduceQueue.java:40-48`；消费 `DelayConsumerQueue.java:50-73`；`DelayQueueInitHandler.java:20-43` ApplicationStartedEvent 启动 N 消费者。`DelayQueueProperties.java:19-46`：prefix=delay.queue、core/max=4、keepAlive=30s、workQueue=256、isolationRegionCount=5。**证实 research/07 §5**。
- **NEW**：惩罚 SPI `ThresholdPenaltyPolicy.java:19-83`——越限累加 VIOLATION_IP/USER，达阈值设 BLOCK_* + TTL；`RedisRateLimitHandler.java`(scene 化 ISSUE_TOKEN vs SECKILL、IP 白名单、ban 检查)。
- **NEW（重要）**：`DelayProduceQueue` 用 Redisson `RDelayedQueue` **而非** `RReliableQueue`(pro 专属)，注释明说可靠性靠消息对账补偿。NearSlot 社区版延迟队列非持久化，须配对账扫描兜底。

## 4. 取消/回滚路径（NEW，最高转移价值）

- `cancel()`(VoucherOrderServiceImpl.java:557-629) @Transactional：查单(user+voucher+NORMAL :558-565)→置 CANCEL(:572-576)→写 RESTORE/CANCEL 对账日志(beforeQty=stock,changeQty=1,afterQty=stock+1 :578-589)→rollbackStock DB(:591)→`result = updateResult && saveLogResult && rollbackResult`(:593)→**仅三者全成功才** rollbackRedisVoucherData(YES, 交换 before/after :595-604)→删订阅状态 hash 字段(:605-607)→Top 买家 ZSet **-1.0**(:608-617)→autoIssue 给最早订阅者(排除取消者 :619-626)。证实 research/03 §5 取消序列 + 新增"三者全成功门"。
- DB 回滚 `SeckillVoucherServiceImpl.java:172-173`→`SeckillVoucherMapper.rollbackStock(voucherId)>0`。
- `RedisVoucherData.java:58-90,92-123`：3 keys+9 args(LogType.RESTORE)、重试 3 次/初始 200ms/最大 1000ms 指数退避+15% 抖动(:125-128)；耗尽写 RollbackFailureLog+Micrometer+告警(:138-159，全 best-effort)。**证实 research/02 §3.3 + NEW 退避抖动细节**。
- 生产端发送失败回滚 `SeckillVoucherProducer.java:34-47`；消费失败 afterConsumeFailure 对 VOUCHER_ORDER_EXIST 设 operate=NO 不清用户 tag(:227-232)，其余 YES+RESTORE/FAIL(:223-255)。
- 回滚 Lua 实为 **37 行**（文档写 38）：get stock(无→10004)→**del stockKey**(非值回补，防漂移)→operate==1 才 srem→写 RESTORE trace→返回 0(:13-37)。证实 research/07 §2.2。

## 5. CORRECTED（现有文档需修正）

1. **orderId ≠ traceId**。research/02 §3.1 / 07 §1 称"同源"，实际两次独立 `nextId()`——`VoucherOrderServiceImpl.java:345-346`（autoIssue :701-702）；二者分别作为 Lua args[5]/args[6] 与 Kafka 消息字段传递。**NearSlot 不应假设共享 ID。**
2. verifyUserLevel(`:388-419`) 仅校验 `allowedLevels` CSV + `minLevel`；`allowedCities` 仅是 AudienceRule 字段(`:425-435`) 未参与实际校验。research/07 §1 "allowedCities 会员准入"言过其实。
3. 回滚 Lua 37 行（非 38）。
4. 对账第四态 INCONSISTENT 为死码。

## 6. NEW — 现有研究均未覆盖

- **秒杀访问令牌闸**(`seckill.access.token.enabled:true`, ttl 30s)：`SeckillAccessTokenServiceImpl.java:27-88` 用 setIfAbsent 发 UUID，`validateAndConsume` 用 compare-and-del Lua(`seckill_access_token.lua:1-8`)；限流 `RateLimitScene.ISSUE_TOKEN` 独立阈值。**可直接作为 NearSlot "抢约令牌"前置闸。**
- "一人一单"是 Lua 内 SET SISMEMBER(`seckillVoucher.lua:35-42`)，候补用独立 ZSet——NearSlot 应保持 booked-set 与 waitlist-ZSet 分离。

## 7. 配置声明（CONFIRMED）

`application.yml`：ShardingSphereDriver(:10-11)、`WRITE_NUMBERS_AS_STRINGS: true`(:27)、Kafka `acks: all`(:31)、`ack-mode: manual_immediate`(:51)。`shardingsphere.yaml:117` broadcast 表含 `tb_rollback_failure_log`——证实 research/07 §6 回滚失败表全局广播，对账日志/路由表按 order_id 分片。

## 8. 对 NearSlot 的落地清单

1. 抢约前置令牌闸（访问令牌 + 独立限流场景）。
2. 候补自动发放两个触发点（取消 + 余量回流）都保留；候选重跑等级校验。
3. 消费端清理 + 通知 + Top 榜的顺序与 best-effort 语义。
4. 回滚"三者全成功门" + 退避抖动 + 全局广播回滚失败表。
5. 社区版延迟队列非持久化 → 对账扫描兜底。
6. orderId 与 traceId 分别生成，不假设同源。
