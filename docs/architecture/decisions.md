---
title: 关键设计决策（ADR）
status: current
source_of_truth:
  - ../../docs/architecture/decisions.md
updated: 2026-11-16
---

# 关键设计决策（ADR）

> 格式：决策 / 理由（含来源项目实证）/ 代价。新增决策追加编号，不修改历史条目。

## ADR-001 采用「模块化单体 + Maven 多模块」而非微服务

- 决策：8 个 Maven 模块，nearslot-app 唯一可执行入口。
- 理由：PickNear 证明单模块可承载复杂域但依赖易腐化；hmdp-plus 证明框架/业务分离的可复用价值。团队规模不需要微服务的运维成本。
- 代价：单体部署上限；扩展期再按 module 边界拆服务。

## ADR-002 认证采用双 Token + 版本号（继承 PickNear）

- 决策：AT=JWT 30min(userId+version)，RT=UUID 7d HttpOnly Cookie，Lua 原子族（LoginSetToken / RefreshExpiredToken / ReadCurrentToken），Caffeine 快拒 + Redis 终校验。
- 理由：PickNear 实证 Token 主动失效 100%、拦截器 ↓50ms（TokenService.java 344 行）。
- 代价：Lua 脚本维护成本；多实例 Caffeine 版本存在秒级不一致窗口（可接受，Redis 兜底）。

## ADR-003 通用能力沉淀为 framework 模块（继承 hmdp-plus）

- 决策：锁/幂等/限流/布隆/延迟队列做成注解 + AOP + 自动装配，业务零侵入。
- 理由：hmdp-plus 五件套分别以 19/7/16/5/12 个类完成，业务模块只写注解。
- 代价：框架模块需要独立测试（hmdp-plus 恰好缺，NearSlot 补齐）。

## ADR-004 抢约一致性走「Lua 预扣 + RabbitMQ 异步落库 + 对账闭环」

- 决策：见 architecture/overview.md §4.1；增加 trace 日志、订单路由表、回滚失败表、缓存失效广播；MQ 选型见 ADR-014。
- 理由：hmdp-plus 生产闭环最完整；PickNear 事务后发送 + 死信 + 幂等标记并入。
- 代价：多 3 张表；对账任务运维。

## ADR-005 Redis 回补必须反转数量语义

- 决策：回补 API 显式传方向，回补时反转 beforeQty/afterQty。
- 理由：hmdp-plus 实证——不反转则对账必然误报。
- 代价：调用方必须携带方向参数，禁止隐式回补。

## ADR-006 分片策略：库=MOD，表=INLINE 整除取模

- 决策：8 张核心表按 hmdp-plus 模式（如 tb_user 表分片 = id 整除 2 再对 2 取模）；手机号 HASH_MOD。
- 理由：配置即分片（hmdp-sharding 仅 1 个 snakeyaml 覆写文件），路由表免二次计算。
- 代价：扩容需迁移；NearSlot 按 2 库 × 2 表起步。

## ADR-007 ID 生成：雪花 + 本地预生成队列（继承 PickNear）

- 决策：启动 Pipeline 批量预取 10000 个 ID 入队，耗尽触发补充；orderId 与 traceId 由同一雪花生成器**分别**取号（源码实证：两次 nextId() 调用，非同一 ID）。
- 理由：PickNear 实测单笔固有耗时 ↓50ms；hmdp-plus 的 workId lua 协调作为备用方案记录。
- 代价：进程重启丢弃队列余量（无业务影响）。

## ADR-008 MQ 可靠性参数组固定（选型已由 ADR-014 调整为 RabbitMQ）

- 决策：publisher-confirm + 消息持久化（persistent）+ 消费手动 ack + 预取批量 + 死信队列；延迟 >10s 丢弃回补（对应源项目 acks=all / 手动 ack 语义）。
- 理由：hmdp-plus application.yml 实证参数组（源项目用 Kafka，属事实记录）；NearSlot 中间件选型见 ADR-014（RabbitMQ）。
- 代价：RabbitMQ 单队列吞吐低于 Kafka 分区——以多队列 + 预取批量缓解；延迟能力用 TTL + 死信队列与 Redisson 延迟队列补位（ADR-009/ADR-014）。

## ADR-009 延迟通知用 Redisson DelayQueue 而非 MQ 延迟主题

- 决策：开抢前 2 分钟提醒、通知控频（去重窗口 300s）走延迟队列框架。
- 理由：hmdp-plus DelayQueueContext + ConsumerTask 模式成熟。
- 代价：Redis 可用性依赖；队列隔离区需初始化 Handler。

## ADR-010 AI Agent 采用两阶段规划 + 双闸口（继承 PickNear）

- 决策：Phase1 决策 → PlanRouter 紧凑目录规划（__UNCERTAIN__ 重跑）→ 多轮编排；PromptHookChain 前置 + GuardedToolCallback 执行闸口；@TargetTool 自动注册自动包 Guard。
- 理由：PickNear 内网零越权实证；O(1) 上下文成本治理实测输入 ~57% 削减。
- 代价：规划链路复杂度高；需策略级单测（guard/hook/plan 全覆盖）。

## ADR-011 观测必须配白名单（PickNear 血泪）

- 决策：ObservabilityTraceFilter 只放行 nearslot.agent. / spring.ai. / gen_ai. 前缀。
- 理由：PickNear 实测 @Scheduled 2s 任务吃掉 21.6 万 units/月 Langfuse 配额。
- 代价：新业务 span 需登记白名单。

## ADR-012 精度双保险：后端数字串化 + 前端 json-bigint

- 决策：jackson 开启 WRITE_NUMBERS_AS_STRINGS；axios transformResponse 用 json-bigint(storeAsString)。
- 理由：雪花 ID 19 位超 JS Number.MAX_SAFE_INTEGER；hmdp-plus 前后端各做一半，NearSlot 双保险。
- 代价：前端需统一用字符串比较 ID。

## ADR-013 密钥治理：.env + 强制校验，禁明文进 compose

- 决策：所有中间件密码/云密钥走 .env，compose 中变量采用「美元符+花括号+VAR+问号+提示语」的强制缺省语法，缺失即拒绝启动；.env 入 .gitignore 并提供 .env.example。
- 理由：PickNear compose 明文硬编码 Redis/RabbitMQ 密码是已知改进点。
- 代价：新环境需分发 .env 模板。

## ADR-014 消息中间件选用 RabbitMQ（而非 Kafka）

- 决策：项目消息统一走 RabbitMQ（publisher confirms + 手动 ack + 死信队列 + TTL 延迟）。
- 理由：PickNear 已在 RabbitMQ 上验证事务后发送/死信/幂等标记全链路；hmdp-plus 用 Kafka 是为秒杀削峰，而 NearSlot 预约场景的峰值远低于券秒杀，且需要灵活路由与延迟能力；团队成熟度与运维成本更优。
- 代价：RabbitMQ 单队列吞吐低于 Kafka 分区；高峰期以多队列 + 预取批量缓解；不在本轮做跨机房复制。

## ADR-015 前后端分离，前端独立模块 nearslot-web

- 决策：前端 Vue3 + TypeScript 6 + Vite + Element Plus + Pinia 独立为 NearSlot/nearslot-web 模块，通过 /api 代理与后端解耦；后端只暴露 REST + SSE。
- 理由：前后端分离便于独立构建/部署（Nginx 托管静态资源 + 反代 /api）；TypeScript 6 strict 保障大型前端可维护性；与 research/11 前端 UX 规范一致。
- 代价：需要维护两套构建与版本握手（C-DOC-04）；跨域与鉴权头需在 Nginx/网关层统一。

## ADR-016 框架能力优先用成熟库，减少造轮子

- 决策：分布式锁/限流/布隆/延迟队列统一用 Redisson；缓存用 Spring Cache + Caffeine + Redis；ID 用 MyBatis-Plus IdWorker；MQ 用 Spring AMQP。仅在成熟库缺失时才自研薄封装。
- 理由：自研锁/延迟队列在可靠性（可重入、看门狗、公平锁、持久化）上难以短时间达到生产级；hmdp-plus 自研五件套的价值在于"教学示范"，NearSlot 的价值在于"用对库 + 讲清原理"。
- 代价：对 Redisson/Spring AMQP 版本与配置有依赖；需在文档中说明选型理由与边界。

## ADR-017 前端工具链采用 Vite+（vite-plus）

- 决策：前端统一使用 Vite+（vite-plus 1.6）作为工具链入口。脚本收敛为 vp dev / vp build / vp check（fmt+lint+type-check 一次执行）/ vp test / vp fmt / vp lint；移除独立 ESLint 9 与 Prettier 配置，改由 Vite+ 内置 oxlint + oxfmt 承担；构建内核为 vite 8（rolldown）。
- 理由：Vite+ 将运行时、包管理器与前端工具链收敛到一个入口，减少多套配置文件与版本对齐成本；vp check 一次调用即完成格式化、lint 与类型检查，直接对接 CI 的"单一命令门禁"；底层 rolldown/oxc 构建速度优于 Rollup 链。
- 迁移方式：官方 vp migrate 自动把 vite 依赖改为 @voidzero-dev/vite-plus-core 并改写 vite.config.ts 的 defineConfig 来源；pnpm 侧通过 pnpm-workspace.yaml 的 catalog + overrides 锁定。
- 代价：Vite+ 生态较新（1.x），部分插件兼容需实测；团队需熟悉 vp 子命令；vp test 与 vp run test（即 vpr）语义不同，已在 standards/frontend.md 与 package.json scripts 固化。
## ADR-019 业务数据库统一 MySQL，彻底移除 H2

- 决策：dev / verify / staging / prod **全部使用 MySQL 8.0** 作为业务库。H2 依赖与配置已于 2026-11-17 全部移除（`nearslot-app/pom.xml` 的 h2 runtime 依赖、`application-verify.yaml` 的 H2 数据源、`verify-schema.sql`）。
- 理由：
  1. **环境同构**：早期 verify profile 用 H2 内存库，导致验证环境与生产不同构。实证问题——`user` 是 H2 保留字，需在 JDBC URL 加 `NON_KEYWORDS=USER` 特判，而 MySQL 无此限制；这类"方言补丁"根本不代表生产行为。
  2. **掩盖真实缺陷**：H2 的 DDL 方言、索引与唯一约束行为、字符集排序均与 MySQL 有差异，"在 H2 上通过"不能推导出"在 MySQL 上通过"。
  3. **成本可控**：MySQL 已由 docker-compose.infra.yml 提供，启动成本与 H2 相当；Testcontainers 亦支持 MySQL。
- 代价：
  1. 单元测试不能再依赖 H2 内存库自动建表——涉及 SQL 语义的测试必须连真实 MySQL（Testcontainers 或本地容器）；
  2. 纯逻辑单测需用 Mock（已在 `ReconciliationTaskTest`、`PaymentServiceTest` 中采用此模式）；
  3. CI 需提供 MySQL service 或 Testcontainers。
- 备选方案（未采纳）：PostgreSQL——需另立 ADR 并评估 ShardingSphere 5.3.2 兼容性与团队熟悉度，当前不切换。

## ADR-018 接口文档采用 Knife4j Next（com.baizhukui）作为 SSOT

- 决策：接口契约以 **Knife4j Next 5.8.2**（`com.baizhukui:knife4j-openapi3-boot4-spring-boot-starter`）为唯一事实源。`/v3/api-docs` 输出 OpenAPI 3.1 JSON 入库到 `api/openapi.json`，`/doc.html` 提供 React 版调试 UI；CI 对入库契约做 diff，破坏性变更必须伴随 `/api/v{N+1}`。
- 理由：这是 Knife4j 的社区维护 fork，明确支持 Spring Boot **2.7 / 3.4 / 3.5 / 4.1**，而原版 `com.github.xiaoymin` 最高 4.5.0 仅适配到 Boot 3.0；保留 `/doc.html`、`/v3/api-docs` 入口与 `knife4j.*` 配置键，迁移成本仅为换 groupId。契约升级到 OpenAPI 3.1（JSON Schema 2020-12），比原版 3.0 更严格。
- 代价：依赖社区 fork 而非原仓库，需关注其发布节奏；UI 为 React 版，与旧版 Vue 版交互有差异；契约入库会增大仓库体积，需定期归档历史版本。

