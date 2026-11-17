---
title: 需求阶段文档索引
status: current
source_of_truth:
  - ../../../docs/lifecycle/02-需求阶段/README.md
updated: 2026-11-16
---

# 需求阶段文档索引（NearSlot）

> 结论：本目录收录需求阶段三份**模板**（需求规格说明书 / 用户需求说明书 / 需求调研记录）。需求的权威事实源是 [quality/functional-requirements.md](../../quality/functional-requirements.md)（FR-1~5）与 [quality/quality-attributes.md](../../quality/quality-attributes.md)（SLO）；本目录只建追溯与表单，**不复制需求正文**。

## 1. 文档索引

| 文档 | 类型 | 状态 | 权威来源 / 说明 |
|---|---|---|---|
| [需求规格说明书.md](需求规格说明书.md) | 模板 | 模板/待填 | FR/SLO 追溯表骨架；权威见 functional-requirements + quality-attributes |
| [用户需求说明书.md](用户需求说明书.md) | 模板 | 模板/待填 | 用户旅程与用户视角需求；旅程基准见 [functional-requirements §3](../../quality/functional-requirements.md) |
| [需求调研记录.md](需求调研记录.md) | 模板 | 模板/待填 | 访谈/调研记录表单 |

## 2. 路由：什么任务读什么

| 任务 | 先读（本目录） | 权威事实源（只链接） |
|---|---|---|
| FR 条目与验收标准 | 需求规格说明书.md | [quality/functional-requirements.md](../../quality/functional-requirements.md) |
| SLO 指标与条件 | 需求规格说明书.md | [quality/quality-attributes.md](../../quality/quality-attributes.md) |
| 用户旅程/验收脚本 | 用户需求说明书.md | [functional-requirements §3](../../quality/functional-requirements.md) |
| 需求覆盖缺口 | 需求规格说明书.md | [research/00-coverage-matrix.md](../../research/00-coverage-matrix.md)、[research/12 §1](../../research/12-quality-governance-deep.md) |
| 需求评审 G1 | 需求规格说明书.md | [quality/review-system.md](../../quality/review-system.md) |
| 调研过程留痕 | 需求调研记录.md | 本模板（待填） |

## 3. 门禁关系

| 门禁 | 与本目录关系 |
|---|---|
| G1 需求 | 需求条目均有验收标准、用户旅程闭环；通过后才进入 G2 设计（见 [review-system](../../quality/review-system.md)） |

## 4. SSOT 声明
- 任何 FR 编号、SLO 数值、旅程步骤均以链接文档为准；本目录仅维护追溯骨架与表单。
- 未产出内容标注「模板/待填」；文档规范见 [standards/doc.md](../../standards/doc.md)。

## 5. 变更记录

| 版本 | 日期 | 变更 | 责任 |
|---|---|---|---|
| v0.1 | 2026-11-16 | 建立需求阶段索引与路由 | lc-plan |
