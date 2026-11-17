---
title: Phase 1 详细开发计划（工程搭建 / G1+G2）
status: current
source_of_truth:
  - ../../../docs/planning/phases/phase-1.md
updated: 2026-11-16
---

# Phase 1 详细开发计划：工程搭建（W1–2 / G1+G2）

> 总纲：[development-plan.md](../development-plan.md) · 任务：[tasks.md](../tasks.md) §2 · 架构：[overview.md](../../architecture/overview.md) · 性能：[guide.md](../../performance/guide.md)

## 1. 目标与前置

- **目标**：交付"可构建、可部署、能力先行"的空骨架——framework 八能力必须先于业务可用。
- **前置**：G0 通过（check_docs + 约束册 + ArchUnit 可用）。
- **出口**：G1 需求评审（开发清单可验收）+ G2 设计评审（架构符合 ADR/overview，无规范违例）。

## 2. 具体开发步骤（D→C→I）

| 步 | 环 | 动作 | 工件 | 验收 |
|---|---|---|---|---|
| 1 | D | 定根 pom：revision+flatten 统一版本，九模块声明与依赖方向 | `pom.xml` | 版本统一；依赖方向符合 ADR-001 |
| 2 | C | 写 ArchUnit 依赖铁律测试（module 禁互依、framework 禁 module、app 独占聚合） | 架构测试类 | 违规样例被检测 |
| 3 | I | nearslot-common：常量/枚举/BaseCode 返回码/Result/异常/UserHolder/ThreadLocal | common 模块 | 零业务依赖；单测绿 |
| 4 | C/I | framework-cache：RedisConstants 键规范 + Caffeine 四层 + 三防（击穿/穿透/雪崩） | cache 子包 | 三防语义测试绿（含 TTL Jitter） |
| 5 | I | framework-lock：LockTemplate（SET NX EX + UUID + Lua 值校验释放 + AutoCloseable） | lock 子包 | 所有者校验释放单测绿 |
| 6 | I | framework-idempotent：@Idempotent SpEL key + SETNX + 业务异常删标记 | idempotent 子包 | 幂等键与删标记测试绿 |
| 7 | I | framework-ratelimit：令牌桶+滑动窗口 Lua + 场景 SPI + 惩罚策略 | ratelimit 子包 | 窗口边界+封禁测试绿 |
| 8 | I | framework-bloom/delayqueue/mq/id：过滤器工厂、延迟队列隔离区+InitHandler、事务后发送+消费模板、雪花预生成队列 | 各子包 | 各模块独立单测绿；延迟队列对账兜底说明落档 |
| 9 | I | nearslot-app 装配；docker-compose 全服务 + healthcheck + .env 强校验 | `nearslot-app/` · `docker-compose.yml` | mvn install 绿；compose 一键起全栈 |

## 3. 落地方案

- **返回码单一枚举**：所有 Lua 返回码在 `BaseCode` 集中定义（防 [09 §F.1](../../research/09-picknear-verification.md) 的 0/1/2 vs 200/501 双口径）；Lua 与 Java 共享码表文档。
- **框架注解化**：业务模块只写注解（@ServiceLock/@RepeatExecuteLimit）， honour ADR-003；框架模块零业务语义。
- **延迟队列兜底**：社区版 `RDelayedQueue` 非持久化（[09b §3](../../research/09b-hmdp-plus-verification.md)），Phase 1 即在对账设计留补偿位（Phase 2 实现）。
- **统一配置**：Hikari 50/10、Lettuce 50/20/5、线程池有界队列+CallerRunsPolicy+NamedThreadFactory（guide.md §4）。

## 4. 闭环方案

| 环 | 验证动作 | 通过标准 |
|---|---|---|
| V-1 | `mvn clean install` + JaCoCo | 编译绿；框架包覆盖率≥80% |
| V-2 | ArchUnit 依赖铁律 | 零违规 |
| V-3 | Testcontainers 起 Redis 跑 Lua 脚本（锁释放/幂等/限流/布隆） | 真实组件语义正确 |
| V-4 | compose 起全栈 + healthcheck 全绿；.env 缺变量拒绝启动 | 一键可用；ADR-013 生效 |
| F | 框架实现暴露设计歧义 → 补 ADR 或修 overview.md | 架构与设计同步 |

## 5. DoD Checklist

- [ ] 九模块可独立构建；revision+flatten 版本统一
- [ ] framework 八能力各有单测，覆盖率≥80%
- [ ] 返回码枚举唯一，无双口径
- [ ] ArchUnit 依赖铁律零违规
- [ ] compose 一键起全栈，密钥全走 .env
- [ ] G1+G2 通过；新增/变更 ADR 已登记

## 6. 风险与回退

| 风险 | 缓解 |
|---|---|
| Spring Boot 4 / Spring AI 2.0 版本落地差异 | 里程碑前复核最新稳定版（overview.md §2 已标注） |
| 框架能力贪多导致延期 | 八能力按"锁/幂等/缓存"优先，其余可并行补齐 |
| 本地与 CI 环境差异 | 一律 Testcontainers + compose，禁本地手工配中间件 |
