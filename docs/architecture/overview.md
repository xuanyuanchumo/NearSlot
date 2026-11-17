---
title: NearSlot 总体架构
status: current
source_of_truth:
  - ../../docs/architecture/overview.md
  - ../../docs/research/03-framework-frontend.md
updated: 2026-11-16
---

# NearSlot 总体架构

## 1. 系统上下文

```
用户(浏览器/App) → Nginx → Vue3 SPA
                ↘ /api → Spring Boot(nearslot-app) → MySQL(ShardingSphere 2 库)
                                                    → Redis 7(Caffeine L1)
                                                    → RabbitMQ(预约/失效广播)
                                                    → DashScope(Spring AI) → Langfuse(OTLP)
```

## 2. 技术栈

| 层 | 选型 |
|---|---|
| 运行时 | JDK 21 LTS，Spring Boot 4.x（4.0.0 落地；保持 Boot 4 不降级） |
| ORM | MyBatis-Plus 3.5.16（mybatis-plus-spring-boot4-starter，type-aliases 分模块注册） |
| 缓存/锁 | Caffeine L1 + Redis 7 L2 + Redisson 3.52.0（分布式锁/限流/布隆/延迟队列；键规范/三防见 performance/guide.md） |
| 消息 | RabbitMQ（publisher confirms + 手动 ack + 死信队列，ADR-014） |
| 分片 | ShardingSphere 5.3.2（MOD 库 + INLINE 表） |
| AI | Spring AI 2.0.1 + DashScope + Langfuse（观测白名单） |
| 前端 | Vue3 + TypeScript 6(strict) + **Vite+（vite-plus 1.6 / vite 8 + rolldown）** + Element Plus + Pinia(persist)，独立模块 nearslot-web（前后端分离，ADR-015；工具链 ADR-017） |
| 部署 | Docker + Nginx（静态资源 + /api 反代）→ Compose（开发/单机生产）→ K8s（扩展期） |

## 3. Maven 多模块（模块化单体）

```
nearslot
├── nearslot-common            常量/枚举/异常/Result/UserHolder（零依赖）
├── nearslot-framework         通用能力（零业务语义）
│   ├── cache    三防+多级缓存+BatchLoadCache
│   ├── lock     LockTemplate(SET NX+UUID+Lua 校验释放)
│   ├── idempotent @Idempotent SpEL 幂等
│   ├── ratelimit  令牌桶+滑动窗口(Lua)+SPI 场景扩展
│   ├── bloom      BloomFilterHandlerFactory(可配容量/误判率)
│   ├── delayqueue Redisson 延迟队列(隔离区+InitHandler)
│   ├── mq         事务后发送+消费模板(重试计数头/死信/幂等标记)
│   └── id         雪花+预生成队列
├── nearslot-module-auth       双 Token+Lua 族+防爆破（PickNear 全套）
├── nearslot-module-shop       服务商/技师/时段/GEO/BloomFilter
├── nearslot-module-appointment 抢约 Lua/候补 ZSet/等级校验/延迟提醒
├── nearslot-module-order      订单路由表/对账日志/回滚失败表/支付回调
├── nearslot-module-agent      两阶段规划/双闸口/提示词降级/观测
├── nearslot-app               装配启动（唯一可执行 jar，唯一依赖全部模块）
└── nearslot-web               前端独立模块（Vue3 + TypeScript 6 + Vite + Element Plus + Pinia；Nginx 托管 + /api 反代，ADR-015）
```

> nearslot-web 为独立前端工程（独立构建与部署），不参与 Maven 多模块构建，故不计入后端模块数量。

**依赖铁律**：module 之间禁止互相依赖（跨模块协作走 common 接口或 RabbitMQ 事件）；framework 不得 import 任何 module；app 除外。

## 4. 关键链路

### 4.1 抢约主链（吸收 hmdp-plus 闭环 + PickNear 手法）

```
POST /appointments
  → 鉴权(AT 版本校验: Caffeine 快拒 → Redis 终校验) + @Idempotent(HTTP 防双击)
  → verifyUserLevel(会员等级)
  → appointment.lua: 余量 DECR + SISMEMBER 一人一时段 + trace 日志(hset) + cjson 结果
  → 返回 appointmentId（雪花预生成队列取号）
  → RabbitMQ(事务提交后发送, publisher-confirm + persistent)
  → 消费端: beforeConsume 延迟门(>10s 丢弃回补) → @RepeatExecuteLimit(uuid)
    → 乐观锁扣 DB 余量 → 落订单+路由表 → 对账日志(BOOK/SUCCESS)
    → 异步: 候补 ZSet 清理 + Top 技师日榜 + 延迟队列(开抢前 2min 提醒)
  → 失败: 回补 Redis(反转 beforeQty/afterQty) + 对账日志(RESTORE/FAIL) + 回滚失败表兜底
```

### 4.2 对账闭环（hmdp-plus 模式）

定时任务：扫描 PENDING 对账单 → 对比 Redis trace 与 DB 订单 → 差异标 ABNORMAL+告警+回补；对账日志与路由表随分片同库。

### 4.3 AI 对话链（PickNear 模式）

Phase1 文本回复 → AfterAiHook 分支 → PlanRouter 紧凑目录规划（__UNCERTAIN__ 保底重跑）→ 多轮编排(≤5) → 工具循环(结果压缩 ≤80 字) → SSE 推流(meta/plan/progress/error/confirm) → 会话落库(决策分支+best-effort)。

## 5. 部署视图

- 开发/单机：docker compose（mysql/redis/rabbitmq/app/nearslot-web，mem_limit 全配，healthcheck 全配，密钥全部 .env）。
- 生产扩展：K8s + HPA；镜像仅 CI 构建（禁本地 build），watchtower/Argo 拉升。
- 观测：prometheus 指标 + Langfuse trace（白名单只放行 nearslot.agent./spring.ai./gen_ai.）。

## 6. 设计决策索引

全部 ADR 见 [decisions.md](decisions.md)（17 条，均标注来源项目与取舍理由）。
