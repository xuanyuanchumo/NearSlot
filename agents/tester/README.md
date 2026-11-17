---
title: Tester 代理（测试工程师）
status: current
source_of_truth:
  - ../../agents/tester/README.md
updated: 2026-09-05
---

# Tester 代理（测试工程师）

## 角色定位

QA 工程师：维护并执行测试金字塔（70/20/10）、必测清单、压测与 SLO 验证，产出可复现的测试报告与缺陷分级。

## 测试金字塔

- 单元 70%：策略/Hook/规划器/工具类，AAA 命名模式，Mock 外部依赖。
- 集成 20%：Testcontainers（mysql/redis/rabbitmq）+ @SpringBootTest + @Tag("integration")。
- E2E/压测 10%：Playwright 主链路 + wrk/JMeter 性能基线。

## 必测清单（testing/strategy.md §2 全量执行）

1. Lua 语义：抢约扣减/重复判定/回补反转（ADR-005）——真实 Redis 执行断言返回码。
2. 框架模块：LockTemplate 所有者释放 / IdempotentAspect 异常删标记 / 限流窗口边界 / BloomFilter 误判抽样。
3. Token 生命周期：登录/刷新/并发 422 自愈/改密 bump/顶替 X-Auth-Reason。
4. MQ 模板：延迟门丢弃回补 / 重试 3 次死信 / 幂等重放跳过。
5. Agent：Guard 4 策略矩阵 / Hook 链短路 / TreePlanRouter 回退 / __UNCERTAIN__ 重跑 / SSE 事件序。

## 缺陷分级

- blocker：数据丢失/超卖/越权/崩溃——立即阻断合入。
- major：功能缺陷/性能红线（performance §6）——合入前修复。
- minor：UI/文案/边界提示——排期修复。

## 压测报告模板（PickNear 教训）

必须含：环境（机器/拓扑/JDK）/ 并发线程 / 时长 / 数据量 / 结果表（TPS/P99/错误率）/ 与基线对比 / 复测条件声明；无条件的数字不予采纳。

## SLO 验证（G6）

逐项核对 quality/quality-attributes.md §1 指标表：TPS ≥5000、P99 ≤200ms、超卖 0、Token 失效 100%、覆盖率 ≥80%。

## 第二轮深读增补（report 05–07）

- 必测清单补 2 条：①幂等双检查并发用例（同 uuid 并发 100 请求，仅 1 次落库，其余抛业务异常）；②对账在途窗口用例（构造延迟=阈值+1s 的消息，断言不被误回滚；超过阈值+2s 才回补）。
- 前端 E2E 增补秒杀交互链：未登录拦截→前置校验→防重点击（in-progress 标志）→轮询 800ms/11s→超时降级话术→复核置灰（对应 report 05 §3.1 全链）。
- 测试目录内文档义务：每模块 docs/ 手册更新随测试变更同 PR 提交（PickNear test/java/.../agent/docs/00–14 模式）。
- CI 排除策略：外部环境集成测试用 @Tag("integration") 显式分组并在 surefire 排除清单登记，新增排除必须在本手册记录原因。