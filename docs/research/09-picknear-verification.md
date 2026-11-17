---
title: PickNear 核验报告（CONFIRMED / CORRECTED / NEW）
status: current
source_of_truth:
  - ../../docs/research/09-picknear-verification.md
updated: 2026-11-16
---

# PickNear 核验报告（源码逐行复核）

> 方法：读取 `research/01,04,05,06,07` 后对 PickNear 源码逐行核验。路径相对 `D:/Projects/TraeProjects/Studying`。性能数字为项目自述、需复测。
> 图例：**CONFIRMED**=现有文档正确 · **CORRECTED**=现有文档有误需修正 · **NEW**=现有研究未覆盖、高转移价值。

## A. AI Agent 执行链（CONFIRMED，含精确行号）

主链：Phase1 流式 → AfterAiHook 决策 → TaskPlanner → TreePlanRouter → MultiRoundOrchestrator → AbstractToolLoop。

| 组件 | 证据 | 要点 |
|---|---|---|
| TaskPlanner | TaskPlanner.java:61-88 / :90-106 / :111-137 | submit 异步入口(CompletableFuture)；completeTurn 仅非 PLANNING/非 paused 落库；resume 走 ConfirmFlowManager.executeApprovedTool |
| MultiRoundOrchestrator | :51 MAX_ROUNDS=5；:96-132 循环 | decompose→planEvent(SSE :117)→execute；:128-129 捕 ConfirmRequiredException→pause |
| AfterAiHookChain | :13,:43-78 | BLOCK>REPLACE>PLANNING>PASS，PLANNING 传染性聚合 :73-76；异常 hook 跳过不阻塞 :48-53 |
| AiResponseRouter | :58-92 | PLANNING 前用 ATTR_PLAN_SEED_OVERRIDE 清洗种子（见 G 诚实机制） |
| SseResponseProcessor | :43-65 | PASS/REPLACE 在 route() 前落库（防快速连发读到旧历史 :52-54） |
| TreePlanRouter | :18-24,:44-63 | 意图树两级路由，单次 plannerCall(:60)，无命中跳过规划不回归全量(:56-59) |

## B. 工具安全双闸口（CONFIRMED）

- ToolGuardManager.java:43-88 投票：BLOCK 立即短路(:77-80)、CONFIRM 聚合、全 ALLOW/ABSTAIN 放行。
- ToolGuardGate.java:62-108 BLOCK 返拦截消息，CONFIRM 在 approvalEnabled 时抛 ConfirmRequiredException(:98-99)，否则退回提示 JSON；guard span 全属性观测(:67-81)。
- 四策略：HighRiskListPolicy.java:36-42、ConfirmToolPolicy.java:33-39、PatternMatchPolicy.java:36-67（BLOCK 优先）、RateLimitPolicy.java:41-77（guard:rate:{conversationId}，Redis 故障降级放行 :71-74）。
- PromptGuardProperties.java:32-55,100-140：配置驱动 block/confirm 工具、pattern、rate-limit、approval(ttlSeconds=300 :119)、toolResult.maxChars=1200(:136)。**NearSlot 加 bookAppointment 到 confirm-tools 无需改代码。**
- GuardedToolCallback.java:141-168 call 装配 ToolInvocationContext；:170-182 callBypass 审批恢复专用。
- ToolCallExecutor.java:37-53 self 占位符解析 + 数字参数转换异常友好化；:84-91 结果截断。

## C. SSE / 上下文 / 记忆（CONFIRMED + 细节）

- StreamingChatInvoker.java:36 MAX_ATTEMPTS=3；:89-104 绕开 ChatClient 直调 ChatModel；stream.contextWrite 写 micrometer 父(:103)保 traceId；:127-143 429 指数退避 `5000*2^(attempt-1)`ms + 错误回喂 LLM。
- AiServiceImpl.java:100-128 异步段 resume 根 span；:129-135 finally closeRootScope 防 Tomcat 线程复用跨会话污染。
- 观测白名单 ObservabilityTraceFilter.java:13-17(21.6万 units/月背景)、:39-51、:54-62 startsWith 白名单外零导出。
- 提示词四级降级 DefaultPromptService.java:21,47-77(remote→local DB→builtin→空串 Fail-Open)；LangfusePromptRepository.java:35-38,64-93 双 Caffeine 缓存；PromptRepositoryAssembler.java:14-16,43-49 未配置→Noop Fail-Open。
- 对话记忆：ConversationReplayServiceImpl.java:53-85 recentMessages fail-open，:88-100 按 conversationId+userId 归属过滤+uptoId 增量游标；RedisConversationMemoryStore.java:31-63 单 key JSON+滑动 TTL。
- 压缩：SubTaskProperties.java:48 compressLength=80（**≤80字摘要**来源）；ResultCompressor.java:26-52 LLM 摘要，失败回退截断。
- AbstractToolLoop.java:94-130 轮循环+预算硬顶，:124-129 触顶强制总结。

## D. 认证 / Token Lua / 拦截器（CONFIRMED）

- TokenService.java:66-105 写 **4 键**(AT/RT/validVersion/currentVersion :73-78)；:107-161 四级校验(JWT :111 / Caffeine 快拒 :116-122 / Redis validVersion :124-135 / 临期随机 :143-147)；:166-256 刷新 + 422 并发自愈；:262-282 带锁刷新(3s)；:284-300 superseded 判定。
- Lua 6 个（RedisLuaConfig.java:39-65）：LoginSetToken.lua:6-27（**直接 SET 覆盖不先 DEL，防主从丢 key**）、RefreshExpiredToken.lua:34-64(431/413/421/422/200)、ReadCurrentToken.lua:25-43（版本守卫防新凭证泄露）、ConsumeVerifyCode.lua:6-10(GET+DEL)、MqSeckill.lua:11-50（SISMEMBER→DECR<0 回补→SADD→HMSET+EXPIRE 86400→TIME 服务器时钟）、RedisUnlock.lua:6-10（值校验才 del）。
- RedisConstants.java:11-20 key+TTL；:42-49 防爆破(IP 60s/5次、24h/10次锁30min)。
- 拦截器 MvcConfig.java:48-57 order 0/1 共享白名单；RefreshTokenInterceptor.java:41-49 Bearer 剥离、:90-126 X-Token-Refresh、:95-100 空 AT 防御、:128-131 >100ms 告警、:165-168 X-Auth-Reason；LoginInterceptor.java:17-34,39-43 掩码日志。

## E. 缓存 / 幂等 / MQ 可靠性（CONFIRMED）

- CacheManager.java:47-66 空值防穿透+setWithJitter；:68-129 逻辑过期+异步重建+抢锁失败返旧值降级；:138-141 TTL 70%~100% 随机；:183-210 异步任务内自抢锁双检。
- IdempotentAspect.java:40-56 SETNX+业务异常删标记；:58-74 SpEL key。SeckillOrderService.java:78 @Idempotent(ttl=10) 与 Lua 内层双层防重。
- LockTemplate.java:44-48 SET NX EX + UUID；:76-88 AutoCloseable close 走 Lua 校验释放。
- AbstractMqConsumer.java:33-34 x-retry-count/MAX_RETRY=3；:51-83 true=ack/false=业务终态 ack/异常=重投；:88-123 重建消息持久化 deliveryMode=2。
- VoucherOrderConsumer.java:27-29,68-89 `seckill:order:processed:{orderId}` TTL 7d 幂等 + DB 乐观锁 stock>0(:75-79)；死信消费者 :57-64。
- VoucherOrderProducer.java:36-47 TransactionSynchronization.afterCommit 事务后发送。
- RedisIdWorker.java:38-95 启动 Pipeline 批量 10000 INCR 入队，id=(timestamp<<32)|incr。

## F. CORRECTED（现有文档需修正）

1. **【重要】MqSeckill.lua 返回码契约不一致**。文档 01 §5 称 Lua 预扣返回 orderId，实际 MqSeckill.lua:13-50 **只返回状态码 200/502/511/501**，orderId 由 Java 生成(SeckillOrderService.java:84)。更严重：SeckillStockService.java:21-24,39-44 自定义 0/1/2 常量（自带"与 SeckillOrderCode 200/501/511 不一致"的自省注释），而 SeckillOrderService.java:52-56 querySeckillVoucher 用 `r!=0` 判成功（把 Lua 的 200 误判为"不能重复下单"），:87 saveOrder 却用 `SeckillOrderCode.SUCCESS(200)` 判成功。两条调用路径口径矛盾，0/1/2 常量疑为陈旧死码。**NearSlot 必须单一 Lua 返回码枚举 + 契约测试。**
2. **"版本号三键"表述不准**（01 §3.1）：实际写 4 键 = AT + RT + validVersion + currentVersion，版本键只有 2 个；应为"Token 体系 4 键"。
3. **"UNKNOWN/UNCERTAIN 保底重跑"**（01 §7.1 / 04 §1）在默认 TreePlanRouter.java:44-63 中未出现，可能在 LegacyPlanRouter；默认路径勿照抄。
4. **"≤80字摘要"**是压缩摘要上限(SubTaskProperties.java:48)，非工具结果截断上限——后者是 `maxResultChars=1200`(PromptGuardProperties.java:136 / GuardedToolCallback.java:44)。两级截断勿混淆。
5. RefreshExpiredToken.lua:8 头注释写 `KEYS[3]=token:version:`，实际 Java 传 `LOGIN_VALID_VERSION_KEY`(TokenService.java:181)；注释陈旧。

## G. NEW（高转移价值，现有研究未覆盖）

### G.1 反编造分层体系 L0–L4（NearSlot 抢约/报价强烈建议继承）

| 层 | 机制 | 证据 |
|---|---|---|
| L0 真值证据 | 工具原始返回旁路登记，异常不影响主链 | ToolEvidence.java:4-7、ToolResultCapture.java:6、DefaultToolResultCapture 在 AbstractToolLoop.java:142-149、ToolExecutionFacade.java:67,149 |
| L1 数据意图兜底 | 数据意图×空计划→诚实兜底，不放行 Phase1 可能编造的数字 | DataIntentClassifier.java:10、DataIntentPromptHook.java:11、DataIntentEmptyPlanFallback.java:7-18，接入 MultiRoundOrchestrator.java:106-110 |
| L3 输出断言闸 | 每轮 summary 离开子 Agent 前确定性断言 | EvidenceAssertionGate.java:11、ClaimExtractor.java:6、HonorAction.java:4 |
| L4 事实账本 | 只收 L0 工具真值；"已核实事实" vs "历史原文仅上下文非事实"区隔 | FactLedgerStore.java:8-10、RedisFactLedgerStore.java:15、ConversationReplayServiceImpl.java:40-44,67-75 |

→ **映射**：NearSlot AI 只能引用工具查得的时段/价格/库存作答；`bookAppointment` 入参须来自已核实事实。

### G.2 其他 NEW

- **双运行时可插拔**：AgentV2Controller.java:26-34 + AgentRuntime 接口；GraphAgentRuntime.java:26-40 按 `agent.access.runtime=graph` 条件启用，默认 LegacyAgentRuntime。NearSlot 可配置切换编排内核不改 Controller。
- **审批恢复绕过二次投票**：GuardedToolCallback.callBypass(:170-182) + ToolCallExecutor.executeBypass(:55-67)，显式 ToolContext 传 userId（异步线程无 UserHolder）。对应 NearSlot `bookAppointment` 强制 CONFIRM→确认后执行。
- **异步会话压缩 dirty 自愈**：CompressTaskLockGuard.java:30-41（tryLock(0) 抢不到置 dirty 绝不阻塞）、CompressionOrchestrator.java:20,42（失败不推进游标，执行后清 dirty）。
- 429 退避 `5000*2^(attempt-1)`ms；配置化 Guard；重复工具调用硬顶(AbstractToolLoop.java:117-121)。

## H. 对 NearSlot 的落地清单

1. 单一 Lua 返回码枚举 + 契约测试（防 F.1 口径矛盾）。
2. 会话版"4 键"模型 + 版本守卫 Lua（防并发刷新凭证泄露）。
3. bookAppointment 加入 confirm-tools 白名单（配置驱动），审批恢复走 bypass 路径。
4. 继承 L0–L4 反编造：AI 报价/时段/库存只引用工具真值，入参来自事实账本。
5. 两级截断分离：摘要 ≤80 字 vs 工具结果 maxResultChars=1200。
6. 默认编排路径按 TreePlanRouter 实证，勿照抄 UNKNOWN/UNCERTAIN 保底重跑说法。
