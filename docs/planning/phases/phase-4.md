---
title: Phase 4 详细开发计划（测试加固 / G4）
status: current
source_of_truth:
  - ../../../docs/planning/phases/phase-4.md
updated: 2026-11-16
---

# Phase 4 详细开发计划：测试加固（W8 / G4）

> 总纲：[development-plan.md](../development-plan.md) · 任务：[tasks.md](../tasks.md) §5 · 测试策略：[strategy.md](../../testing/strategy.md) · 性能：[guide.md §5](../../performance/guide.md)

## 1. 目标与前置

- **目标**：用数据证明质量——覆盖率、压测、安全三项均带证据，杜绝"无条件的压测数字"。
- **前置**：Phase 2、Phase 3 功能完成；G4 总验收需在两者之后。
- **出口**：G4 测试评审——行覆盖≥80%（核心≥85%）、SLO 达标且数字带条件、0 越权。

## 2. 具体开发步骤（D→C→I）

| 步 | 环 | 动作 | 工件 | 验收 |
|---|---|---|---|---|
| 1 | D | 按金字塔 70/20/10 盘点现有测试缺口（单元/集成/E2E） | 缺口清单 | 每缺口对应必测清单条目 |
| 2 | I | 单元层补齐：策略/Hook/规划器/工具类 JUnit5+Mockito+AssertJ | 单测集 | 覆盖率达标 |
| 3 | C/I | Testcontainers 集成：统一 mysql/redis/rabbitmq，与生产同构 SQL | 集成测试类（@Tag("integration")） | 可重复执行 |
| 4 | C | Lua 契约测试：返回码单一枚举断言（防 09 §F.1 双口径重现） | 契约测试类 | 双口径样例被拦截 |
| 5 | I | E2E：主链路（预约/取消/候补/AI 对话）Playwright | E2E 脚本 | 全绿 |
| 6 | I | 压测：wrk/wrk2 + JMeter（含 SSE），记录机器/线程/时长/数据量/拓扑/JDK | `docs/performance/benchmarks.md` | P99≤200ms、TPS≥5000、错误率<0.1%，条件齐全 |
| 7 | I | 安全审计：Token 失效 100%、越权/注入/密钥扫描、依赖漏洞 | 审计报告 | 0 越权、0 明文密钥 |
| 8 | V | 缺陷分级清零：blocker/major/minor，台账 | 缺陷台账 | blocker=0 |

## 3. 落地方案

- **覆盖率**：JaCoCo 报告入 CI；核心包 auth/appointment/order ≥85%，其余 ≥80%（strategy.md §1）。
- **压测条件模板**：`机器规格 | 并发线程 | 时长 | 数据量 | Redis/DB 拓扑 | JDK 版本 | 结果`——缺一不采纳（guide.md §5）。
- **集成隔离**：`-DexcludedGroups=integration` 默认排除；CI staging 触发全量（deployment/guide.md §4）。
- **时间确定性**：Clock 注入；禁止共享可变静态状态（strategy.md §3）。
- **快照断言**：JsonAssert 宽松模式，避免脆弱全等。

## 4. 闭环方案

| 环 | 验证动作 | 通过标准 |
|---|---|---|
| V-1 | CI 跑全套（单测+契约+JaCoCo） | 覆盖率门禁绿 |
| V-2 | staging 跑 @Tag("integration") 全量 | Testcontainers 用例全绿 |
| V-3 | 压测报告评审 | 11 项 SLO 逐条对照，数字带条件 |
| V-4 | 安全审计复测 | 0 越权、0 明文、依赖无高危漏洞 |
| V-5 | 缺陷台账复核 | blocker=0；全部分级 |
| F | 缺陷暴露设计问题 → 补 ADR/规范；同类两次 blocker 反哺 standards | 规范与代码同步 |

## 5. DoD Checklist

- [ ] 覆盖率≥80%（核心≥85%）且 JaCoCo 报告入 CI
- [ ] 必测清单逐项通过（Lua/框架/Guard/Token/MQ）
- [ ] 压测报告条件齐全，SLO 全部达标
- [ ] 安全审计 0 越权 0 明文密钥
- [ ] 缺陷 blocker=0
- [ ] G4 通过

## 6. 风险与回退

| 风险 | 缓解 |
|---|---|
| 压测数字被质疑 | 强制条件模板；影子库独立环境 |
| 集成测试不稳定 | Testcontainers + 固定 Clock + 禁共享状态 |
| 覆盖率数字注水 | 核心包单独设 85% 门槛；reviewer 抽检断言质量 |
