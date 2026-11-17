---
title: 计划阶段文档索引
status: current
source_of_truth:
  - ../../../docs/lifecycle/01-计划阶段/README.md
updated: 2026-11-16
---

# 计划阶段文档索引（NearSlot）

> 结论：本目录是 NearSlot 计划阶段（Phase 0 治理就绪 W0、Phase 1 工程搭建 W1–2）的**表单/索引模板**入口。**进度、任务、门禁事实不在本目录复制**，一律链接到 docs/planning/ 与 docs/quality/ 的权威文档。

## 1. 文档索引

| 文档 | 类型 | 状态 | 权威来源 / 说明 |
|---|---|---|---|
| [项目开发计划.md](项目开发计划.md) | 模板 | 模板/待填 | W1–W9 进度、里程碑、门禁映射；依据 [roadmap](../../planning/roadmap.md) / [tasks](../../planning/tasks.md) / [development-plan](../../planning/development-plan.md) |
| [可行性分析报告.md](可行性分析报告.md) | 模板 | 模板/待填 | 技术/经济/操作/法律/进度五维；证据取自两个源项目 |
| [立项申请审批表.md](立项申请审批表.md) | 模板 | 模板/待填 | 立项审批表单；审批结论待填 |

## 2. 路由：什么任务读什么

| 任务 | 先读（本目录） | 权威事实源（只链接，勿复制） |
|---|---|---|
| 排期与阶段里程碑 | 项目开发计划.md | [planning/roadmap.md](../../planning/roadmap.md)、[phases/phase-0.md](../../planning/phases/phase-0.md) 至 [phase-5.md](../../planning/phases/phase-5.md) |
| 任务分解 WBS | 项目开发计划.md | [planning/tasks.md](../../planning/tasks.md) |
| 落地与闭环方法（DCIVF） | 项目开发计划.md | [planning/development-plan.md](../../planning/development-plan.md) |
| 门禁 G0–G6 定义 | 立项申请审批表.md | [quality/review-system.md](../../quality/review-system.md) |
| 需求是否成立/研究覆盖 | 可行性分析报告.md | [research/00-coverage-matrix.md](../../research/00-coverage-matrix.md)、[research/12-quality-governance-deep.md](../../research/12-quality-governance-deep.md) |
| 责任代理与分工 | 项目开发计划.md | [agents/overview.md](../../agents/overview.md) |

## 3. 门禁映射概览（权威：[review-system.md](../../quality/review-system.md)）

| 门禁 | 全称 | 阶段 | 与本目录的关系 |
|---|---|---|---|
| G0 | 治理就绪 | Phase 0 / W0 | 立项前置：治理脚本、约束册、验收矩阵、.env.example 就绪且人签 |
| G1 | 需求 | W1–2 | 需求规格说明书（见 [02-需求阶段](../02-需求阶段/README.md)）过审后方可进入设计 |
| G2 | 设计 | W1–2 | 架构与 ADR 评审，见 [architecture/decisions.md](../../architecture/decisions.md) |

## 4. SSOT 声明
- 本目录文件为**模板与索引**，不承载事实；任何日期、数字、FR/SLO/ADR 编号均以链接文档为准。
- 未产出内容处标注「模板/待填」；禁止把权威文档正文粘贴进本目录。
- 文档编写规范见 [standards/doc.md](../../standards/doc.md)；研究背景见 [research/06-engineering-lifecycle-research.md](../../research/06-engineering-lifecycle-research.md)。

## 5. 变更记录

| 版本 | 日期 | 变更 | 责任 |
|---|---|---|---|
| v0.1 | 2026-11-16 | 建立目录索引与路由（模板/待填） | lc-plan |
