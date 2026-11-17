---
title: 质量与配置文档索引
status: current
source_of_truth:
  - ../../../docs/lifecycle/07-质量与配置/README.md
updated: 2026-11-16
---

# 质量与配置文档索引（NearSlot）

> 结论：本目录收录质量与配置域六份**模板**。门禁定义、SLO、评审流程与 ADR 的权威事实分别在 [quality/review-system.md](../../quality/review-system.md)、[quality/quality-attributes.md](../../quality/quality-attributes.md)、[architecture/decisions.md](../../architecture/decisions.md) 与 [standards/git.md](../../standards/git.md)；本目录只建计划/登记/度量表单，**不复制权威正文**。

## 1. 文档索引

| 文档 | 类型 | 状态 | 权威来源 / 说明 |
|---|---|---|---|
| [质量保证计划.md](质量保证计划.md) | 模板 | 模板/待填 | 引用 [review-system](../../quality/review-system.md) 的 G0–G6 与五维检查单 |
| [配置管理计划.md](配置管理计划.md) | 模板 | 模板/待填 | 分支/标签/版本依 [git.md](../../standards/git.md)；密钥见 ADR-013 |
| [风险管理计划.md](风险管理计划.md) | 模板 | 模板/待填 | NearSlot 顶级风险登记表 |
| [度量分析.md](度量分析.md) | 模板 | 模板/待填 | 覆盖率/SLO/缺陷密度/门禁通过率 |
| [会议记录.md](会议记录.md) | 模板 | 模板/待填 | 会议纪要表单 |
| [决策分析报告.md](决策分析报告.md) | 模板 | 模板/待填 | 链接 [ADR](../../architecture/decisions.md)，不复制决策正文 |

## 2. 路由：什么任务读什么

| 任务 | 先读（本目录） | 权威事实源（只链接） |
|---|---|---|
| 门禁与评审流程 | 质量保证计划.md | [quality/review-system.md](../../quality/review-system.md) |
| 质量目标与 SLO | 质量保证计划.md / 度量分析.md | [quality/quality-attributes.md](../../quality/quality-attributes.md) |
| 测试策略 | 质量保证计划.md | testing/strategy.md（[reference](../../testing/strategy.md)） |
| 分支/提交/版本/标签 | 配置管理计划.md | [standards/git.md](../../standards/git.md) |
| 密钥与 .env | 配置管理计划.md | ADR-013（[decisions.md](../../architecture/decisions.md)） |
| 风险登记 | 风险管理计划.md | [research/12](../../research/12-quality-governance-deep.md)、[research/06](../../research/06-engineering-lifecycle-research.md) |
| 度量口径 | 度量分析.md | [quality-attributes](../../quality/quality-attributes.md)、[development-plan §3](../../planning/development-plan.md) |
| 架构决策 | 决策分析报告.md | [architecture/decisions.md](../../architecture/decisions.md) |

## 3. 门禁全景（权威：[review-system.md](../../quality/review-system.md)）

| G0 治理就绪 | G1 需求 | G2 设计 | G3 代码 | G4 测试 | G5 发布 | G6 验收 |
|---|---|---|---|---|---|---|
| lead+人 | 代理组+人 | Reviewer | Reviewer | Tester | Reviewer+人 | 代理组+人 |

## 4. SSOT 声明
- 门禁名称/通过标准、SLO 数值、ADR 内容一律链接权威文档；本目录表单不得改写。
- 未产出内容标注「模板/待填」；文档规范见 [standards/doc.md](../../standards/doc.md)。

## 5. 变更记录

| 版本 | 日期 | 变更 | 责任 |
|---|---|---|---|
| v0.1 | 2026-11-16 | 建立质量与配置索引与路由 | lc-plan |
