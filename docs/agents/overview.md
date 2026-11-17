---
title: 代理协作总览
status: current
source_of_truth:
  - ../../docs/agents/overview.md
updated: 2026-09-05
---

# 代理协作总览（AI-Native 开发协作）

## 1. 四代理分工

| 代理 | 目录 | 职责 | 主文档 |
|---|---|---|---|
| designer | agents/designer | 架构设计/技术选型/ADR 撰写/表与接口设计 | architecture/、api/、database/ |
| developer | agents/developer | 后端实现/框架模块/测试编写/性能落地 | standards/coding.md、docs 各规范 |
| reviewer | agents/reviewer | 门禁 G2/G3/G5 评审/预审意见/规范反哺 | quality/review-system.md |
| tester | agents/tester | 测试策略执行/必测清单/压测与 SLO 验证 | testing/strategy.md、quality/quality-attributes.md |

## 2. 协作流程（对应六门禁）

```
designer（G1 需求→G2 设计：ADR+表+接口）
  → developer（G3 实现+自检 → G4 单测/集成）
  → tester（G4 验证：覆盖率/必测清单/Lua 语义/压测）
  → reviewer（G3/G4 预审 → G5 发布检查单 → G6 SLO 验收）
  → 争议/新约束回流 designer（ADR 反哺）与 standards/（规范反哺）
```

## 3. 协作铁律

- 每个代理产出必须落文件（代码/文档/报告），不允许只给口头结论。
- 跨代理交接必须附带证据：评审带文件行号、测试带数据、设计带 ADR 编号。
- 规范冲突以 docs/ 为准；docs 未覆盖的先补文档再实现（默认不新建文档规则除外，走 README 登记）。
- 所有代理遵守 AGENTS.md（工作区根）与本文档；单个代理 README 是其专业约束的展开。

## 4. 各代理输出模板

- designer：ADR（决策/理由/代价）+ 模块依赖图 + 表清单变更 + 接口变更。
- developer：PR（背景/变更/自检/测试证据/影响面）+ 必测项自查表。
- tester：测试报告（金字塔占比/覆盖率/必测清单结果/缺陷分级 blocker|major|minor）。
- reviewer：评审意见清单（文件+行号+级别+建议）+ 门禁结论（通过/有条件通过/驳回）。
