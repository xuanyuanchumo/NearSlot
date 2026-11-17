---
title: 测试策略
status: current
source_of_truth:
  - ../../docs/testing/strategy.md
updated: 2026-11-16
---

# 测试策略

## 1. 测试金字塔

| 层 | 占比 | 范围 | 工具 |
|---|---|---|---|
| 单元 | 70% | 策略/Hook/规划器/工具类 | JUnit5 + Mockito + AssertJ |
| 集成 | 20% | Controller + Redis/MySQL/RabbitMQ 真实组件 | Testcontainers + @SpringBootTest |
| E2E/压测 | 10% | 主链路 + 性能基线 | Playwright + wrk |

- 覆盖率门禁：行覆盖 ≥80%，核心包（auth/appointment/order）≥85%；JaCoCo 报告入 CI。
- 集成测试打 @Tag("integration")，CI 默认排除（PickNear 显式 !类名 模式升级为 tag 模式）。

## 2. 必测清单（两项目短板反推）

- Lua 语义测试：MqSeckill/回补反转语义（ADR-005 必测）、幂等 Set、trace 日志字段——用 Testcontainers-Redis 执行真实脚本断言返回码与数据。
- 框架模块独立单测：LockTemplate（所有者校验释放）、IdempotentAspect（业务异常删标记）、限流器窗口边界、BloomFilter 误判率抽样——hmdp-plus 缺失项，NearSlot 强制补齐。
- Guard/Hook/Plan 策略级测试（PickNear 模式）：4 Policy 投票矩阵、Hook 链短路、TreePlanRouter 回退 Legacy、__UNCERTAIN__ 重跑。
- Token 全生命周期：登录/刷新/并发刷新 422 自愈/改密 bump/顶替判定（X-Auth-Reason 断言）。
- MQ 消费模板：延迟 >10s 丢弃回补、重试 3 次进死信、幂等标记重放跳过。

## 3. 测试数据与环境

- Testcontainers 统一起 mysql/redis/rabbitmq；SQL 用 sql/ 初始化脚本，保证与生产同构。
- 测试禁止共享可变静态状态；时间相关用 Clock 注入。
- 快照/JSON 断言用 JsonAssert 宽松模式，避免脆弱全等。

## 4. 验收基线（对应 quality/slo）

- 功能：主链路（预约/取消/候补/AI 对话）E2E 全绿。
- 性能：空接口 TPS ≥5000、抢约链路 P99 ≤200ms（记录条件的压测报告）。
- 安全：Token 失效 100%、高危工具零越权（Guard 策略全绿）。
- 质量：覆盖率门禁通过 + check_docs.py 通过 + commitlint 通过。
