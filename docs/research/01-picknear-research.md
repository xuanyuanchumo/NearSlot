---
title: PickNear 深度研究报告
status: current
source_of_truth:
  - ../../docs/research/01-picknear-research.md
project: PickNear（探点）
updated: 2026-09-05
---

# PickNear 深度研究报告

> 研究方法：源码通读（主源码 434 文件 + 测试 102 文件）+ 官方文档（md/ 规范、agent、report）+ 配置/CI/部署脚本交叉验证。
> 所有结论标注证据来源；性能数字为项目自述的历史压测结果，复用前必须复测。

## 1. 项目定位

- 单模块 Spring Boot 3.4.4 + Java 17（根包 `com.hmdp`），在开源点评项目（黑马点评）基础上二次开发，主打**认证安全、高并发秒扣、AI Agent 对话**三大亮点。
- 部署形态：Nginx(前端) → Spring Boot(8082) → MySQL/Redis/RabbitMQ，Docker Compose 编排，镜像由 GitHub Actions 构建并推送阿里云 ACR。
- 前端独立仓库：Vue 3.5 + Vite 8 + TS + Element Plus（dev 3000）。

## 2. 工程结构（单模块内的"逻辑微服务"）

包结构按业务域+能力域双层组织，是该项目的第一大工程亮点——**单 Maven 模块内实现"模块化单体"**：

| 包 | 职责 | 代表类 |
|---|---|---|
| `auth/` | 认证子域（自包含 controller/dto/entity/login/token/verifycode） | TokenService(344L)、SessionContextService、LoginStrategy 注册表 |
| `interceptor/` | 双拦截器链 | RefreshTokenInterceptor(176L) → LoginInterceptor(47L) |
| `common/` | 缓存/幂等/锁 | CacheManager(213L)、IdempotentAspect(76L)、LockTemplate(91L) |
| `utils/redis` | Redis 常量/ID/脚本 | RedisConstants(62L)、RedisIdWorker(140L)、LockFreeRedisScript |
| `voucher/` | 秒杀子域（controller/mq/order/stock） | SeckillOrderService(131L)、MqSeckill.lua |
| `content/` | 博客/Feed/关注 | FeedPushService、FollowServiceImpl(11KB) |
| `agent/` | AI Agent（27 个子包，200+ 类，详见 §7） | TaskPlanner、ToolGuardManager、AgentTracer |

计数注解：@RestController 16 / @Service 18 / @Configuration 33 / @Aspect 3；resources 含 6 个 Lua 脚本 + prompts/（26 个提示词模板）。

## 3. 认证与安全体系（ NearSlot 必抄清单 ）

### 3.1 双 Token + 版本号

- AT = JWT 30min（claims: userId + version），请求头 `authorization: Bearer <token>`（统一小写）。
- RT = UUID 7d，HttpOnly Cookie（SameSite=Lax / HTTPS 下 Secure+SameSite=None）+ 请求头 `Refresh-Token` 双通道兜底。
- Token 体系 4 键（AT + RT + 2 个版本键）：`token:version:valid:{userId}`（校验用，8d）、`token:version:current:{userId}`（最新，8d）。登录/改密 bump（redisIdWorker.nextVersion），**刷新不 bump**（取舍：减少 Lua 写冲突，安全性由 RT 轮转保证）。
- 校验四级流水线：JWT 签名过期 → Caffeine `tokenValidVersionCache`（5min）快速拒绝 → Redis validVersion 终校验 → 临期判定（剩余 <5~10min 随机值触发刷新）。

### 3.2 Lua 原子脚本族（6 个）

| 脚本 | 作用 | 关键设计 |
|---|---|---|
| LoginSetToken.lua | 登录原子写 4 键 | 不先 DEL 再 SET，防主从切换丢 key |
| RefreshExpiredToken.lua | 刷新 AT/RT/版本 | 返回码 200/413/421/422/431 区分被顶替/无会话/RT 不匹配 |
| ReadCurrentToken.lua | 422 并发自愈 | 版本守卫：validVersion==oldVersion 才回当前凭证，不泄露新登录 |
| ConsumeVerifyCode.lua | 验证码 GET+DEL 原子消费 | 防重复使用 |
| MqSeckill.lua | 秒杀预扣 | 见 §5 |
| RedisUnlock.lua | 锁释放校验 value | 仅锁所有者可释放 |

### 3.3 防御纵深

- 防爆破：`login:fail:ip:`（60s 5 次）+ `login:fail:count:`（24h 10 次锁 30min）。
- 密码：BCrypt，兼容旧 MD5+盐并在登录成功时自动升级。
- 诚实声明文化：文档明确"这是并发刷新自愈，不是完整 RT Reuse Detection"——不夸大，值得继承。

## 4. 拦截器与多级缓存（量化：拦截器 ↓50ms、TPS 2800→5500）

- RefreshTokenInterceptor 承担：鉴权 + 刷新 + 上下文装配；响应头协议 `X-Token-Refresh: ok|skipped|failed` + `X-Auth-Reason: superseded|no-session`；AT 防御性检查（空不下发，防 Bearer null 401 风暴）；耗时 >100ms 告警日志。
- 缓存四层：Caffeine L1（版本号 5min / 用户信息 20min+10min 异步刷新，刷新期返旧值）→ Redis L2（Pipeline 批量）→ Caffeine userinfoCache（BatchLoadCache 每 2s 双 Set 防并发批量回填）→ MySQL L3。
- 三防：击穿=逻辑过期+异步重建+抢锁失败 null 降级；穿透=空值 "" 短 TTL（有效 1h/无效 5min）；雪崩=TTL 随机偏移 70%~100%。
- LockTemplate：SET NX EX + UUID value，Lua 校验 value 释放（仅所有者可释放）；AutoCloseable LockHandle + withLock 模板。

## 5. 秒杀与 MQ

- 链路：Lua 预扣（SISMEMBER 一人一单 → DECR<0 回补 → SADD → HMSET 订单摘要）→ **Lua 仅返回状态码(200/502/511/501)，orderId 由 Java 侧生成**(详见 09 §F.1) → RabbitMQ 异步落库（消费端 stock>0 乐观锁兜底）。
- 两层防重：`@Idempotent`（HTTP 层，SpEL key，TTL 10s，业务异常自动删标记）+ Lua 内层原子判断。
- MQ 可靠性：事务提交后发送（TransactionSynchronization.afterCommit，事务内不发 MQ）；AbstractMqConsumer 模板（true=ack / false=业务终态 ack / 抛异常=x-retry-count 重试，≥3 进死信）；幂等标记 `seckill:order:processed:{orderId}` TTL 7d。
- ID 生成：RedisIdWorker 雪花 + **本地预生成队列**（启动 Pipeline 批量 INCR 10000 入队，耗尽再补），单笔固有耗时 ↓50ms。

## 6. 社交与店铺

- Feed 推模式：事务提交后 Pipeline 批量写粉丝 ZSet（score=时间戳），滚动分页 + 懒回填（空箱回填最近 20 篇）+ 新关注回填。已知瓶颈：百万粉丝全量遍历（面试自省项）。
- 点赞：Set（一人一赞）+ ZSet（时间戳 top5）+ 3s 分布式锁。
- 关注：双向 Set + intersect 共同关注 + 空值防穿透。
- 店铺：GEO 按 typeId 分组 + 双模式查询 + `ORDER BY FIELD` 保序 + **排序字段白名单**（score/sold/comments/avgPrice）防注入。

## 7. AI Agent 体系（27 子包，最大差异化资产）

### 7.1 主链路

两阶段：Phase1 无工具文本回复 → AfterAiHook 决策（PASS/REPLACE/BLOCK/转规划）→ Phase2 PlanRouter 规划（默认 TreePlanRouter：紧凑工具目录「工具名+短标签+参数名」，一次 LLM 调用完成选工具+定参数，`__UNCERTAIN__` 保底重跑）→ MultiRoundOrchestrator 编排（MAX_ROUNDS=5，ConfirmRequiredException 暂停恢复）→ AbstractToolLoop 手动工具循环（结果压成 ≤80 字摘要入历史，**上下文累积 O(1)**，输入 token ~57% 削减）。

### 7.2 安全双闸口

1. 前置：PromptHookChain（注入检测本地规则+正则，Redis 仅限流辅助，异常降级不阻塞）。
2. 执行：GuardedToolCallback 包装所有工具（@TargetTool 注解启动自动扫描注册+自动包 Guard，防人工遗漏）→ ToolGuardManager 遍历 4 Policy 投票（HighRiskList/ConfirmTool/PatternMatch/RateLimit），BLOCK 短路、CONFIRM 走审批（TTL 300s）、ALLOW 放行；@RequiredDataPermission AOP 数据归属兜底。

### 7.3 可观测与提示词

- AgentTracer 门面（SpanLifecycle+AttributeSanitizer+traceEnabled 总开关+TraceBackend 可插拔），Micrometer Observation → OTel → OTLP → Langfuse；**观测白名单**只放行 `agent./spring.ai./gen_ai.` 前缀（否则 @Scheduled 2s 任务实测吃掉 21.6 万 units/月配额）。
- traceId 串树两根因修复：span 生命周期时序错误 + Reactor context 污染（流式直连 model 层 + contextWrite 显式传父）。
- 提示词四级降级：Langfuse → 本地 DB → 内置模板 classpath → Fail-Open；LangfusePromptRepository 双 Cache（content + failure 防抖）。
- 历史会话：自建 agent_conversation/agent_message（(conversation_id,user_id) 归属校验），按 Hook 决策分支落库，PLANNING 回合只落最终合并答案，best-effort 不阻塞主链路。

## 8. 部署与 CI/CD

- Dockerfile 多阶段：builder（go-offline+resolve-plugins 依赖层缓存，GHA 镜像层而非 mount cache——**GHA runner 不共享 mount cache**）→ layertools 拆层 → JRE alpine（先建用户再 COPY --chown 避免重写整层；HEALTHCHECK /actuator/health；JAVA_OPTS RAMPercentage 50/80）。
- compose：mem_limit 全家桶（mysql 1g/redis 256m/rabbitmq 512m/app 1g/nginx 128m）；depends_on condition: service_healthy；watchtower 标签自动更新；⚠️ Redis/RabbitMQ 密码明文硬编码在 compose（改进点，NearSlot 全部走 .env + ${VAR:?} 强制）。
- CI（ci-cd.yml）：changes job 用 git diff 判定纯文档变更跳过编译 → check_docs.py 文档检查 → JDK17 mvn compile/test（排除集成测试类）→ 仅 push master 构建推 ACR（provenance/sbom 关闭因 ACR 个人版不支持）→ 飞书/钉钉通知可缺省跳过。
- AGENTS.md 工程纪律：**禁止本地 docker build**（镜像只在 CI 构建）；密钥在 .env；部署 = compose pull + up -d --no-build。

## 9. 文档治理（md/ 目录，NearSlot 文档体系蓝本）

- 文档规范（唯一事实源）：frontmatter `status: draft|current|legacy` + `source_of_truth`（指向真实代码路径）+ `superseded_by`；模块=一级目录；默认不新建文档；历史靠 Git 不建 archive/。
- check_docs.py 确定性检查：frontmatter 必填、链接存在、current 文档可从模块 README 到达、source_of_truth 存在且被 Git 跟踪。
- Git 规范：`<type>(<scope>): <subject>`（subject 中文≤50 字祈使句，body 每行≤72 字符 What+Why），commitlint+husky 强制；type 枚举 10 个 + scope 表。

## 10. 质量保障现状

- 测试 102 个：guard 4 策略、hook 3 实现、honesty gate、history compression/fidelity/ledger/pressure、plan router、prompt repo、permission validator——**Agent 子域测试密度最高**；CI 排除 4 个集成测试类。
- 量化成果（自述）：Token 主动失效 100%；拦截器 ↓50ms；空接口 TPS 2800→5500；wrk 峰值 16000+；订单 ID ↓50ms；DB 瞬时查询 ↓90%、零超卖。
- 已知风险/自省（面试官 6 问）：刷新不 bump 的安全取舍、Caffeine 多实例版本不一致窗口、压测条件未记录、EVALSHA 与 Redis 集群 slot、点赞锁 3s 超时边界、Feed 百万粉丝遍历。

## 11. 对 NearSlot 的吸收清单

1. 双 Token + 版本号 + Lua 脚本族全套认证体系（含响应头协议与前端 401 处理顺序）。
2. 单模块"逻辑微服务"包组织 + 领域子包自包含（auth/agent 样板）。
3. CacheManager/BatchLoadCache/Idempotent/LockTemplate 四件套通用组件。
4. 事务后发 MQ + 重试计数头 + 死信 + 幂等标记模板。
5. AI Agent：两阶段规划、双闸口、@TargetTool 自动注册、紧凑工具目录、O(1) 上下文、观测白名单、提示词降级、历史分支落库。
6. 文档治理（frontmatter+check_docs.py）、Git 规范（commitlint+husky）、CI 文档变更跳过编译。
7. Dockerfile 分层与构建纪律（禁本地 build、mem_limit、healthcheck、先建用户再 COPY）。
8. 诚实声明与"量化成果必须可复现"的工程文化。

—— 完 ——
