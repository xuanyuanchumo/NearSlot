---
title: NearSlot 研究维度覆盖矩阵（21 维）
status: current
source_of_truth:
  - ../../docs/research/00-coverage-matrix.md
updated: 2026-11-16
---

# NearSlot 研究维度覆盖矩阵（21 维）

> **用途**：用户要求的 21 个研究维度逐项落位，标明"现有文档覆盖 → 缺口 → 承载文档"，杜绝遗漏与漂移。
> **图例**：✅ 已充分覆盖 · 🟡 部分覆盖需深化 · 🔴 缺口需新建 · 📄 承载文档路径。

## 1. 总览矩阵

| # | 维度 | 现状 | 承载文档 | 缺口说明 |
|---|---|---|---|---|
| 1 | 功能性需求 | ✅ | docs/quality/functional-requirements.md（FR-1~5） | 验收标准仅抽样，需全量细化 |
| 2 | 非功能性需求 | ✅ | docs/quality/quality-attributes.md（SLO 表） | 指标有条件标注，部分待复测 |
| 3 | 设计约束 | 🟡 | docs/architecture/decisions.md（ADR-001~013） | 约束分散在 ADR，需集中成约束清单 |
| 4 | UX/UI 标准 | ✅ | docs/standards/frontend.md + research/05 | 需补 design token / 可访问性细节 |
| 5 | 工程化规范 | ✅ | docs/standards/{coding,git,doc,frontend}.md | 需补版本号同步、漂移治理 |
| 6 | 功能与业务 | ✅ | functional-requirements + research/08 | 业务域 FR-1~5 已定义，特色域已深化 |
| 7 | 架构与设计 | ✅ | architecture/overview.md + ADR | 模块边界清晰 |
| 8 | 代码与工程 | 🟡 | standards/coding.md + testing/strategy.md | 中文注释规范需细化（见 #15） |
| 9 | 日志与错误体系 | 🟡 | api/design.md §3 错误码 + quality-attributes §3 监控 | 缺统一日志规范/traceId 传播/分级 |
| 10 | 版本号同步管理 | 🔴 | 散见 ADR-002/012 | 缺跨端版本同步专章 |
| 11 | 测试体系 | ✅ | docs/testing/strategy.md + research/06 | 已覆盖金字塔/必测清单 |
| 12 | 用户旅程 | 🟡 | functional-requirements §3 + research/04 §5 | 需扩展全 journeys + 状态机 |
| 13 | 非功能与运维 | ✅ | deployment/guide + performance/guide + quality-attributes | 已覆盖 |
| 14 | 评审与验收 | ✅ | quality/review-system.md（G0~G6 五维） | 已覆盖 |
| 15 | 中文注释 | 🟡 | standards/coding.md 提及 | 需成体系（Javadoc/注释漂移） |
| 16 | 现代响应式布局 | 🟡 | standards/frontend.md + research/03 §3 | 需 container query/流式排版深化 |
| 17 | 合理数据结构与算法/数学模型 | 🟡 | research/03 §4 + research/07 §5 | 缺数学模型（排队论/概率）专节 |
| 18 | 模块化生产级架构 | ✅ | architecture/overview.md §3 + ADR-001/003 | 已覆盖 |
| 19 | 功能域规划 | ✅ | overview §3 模块 + functional-requirements FR 域 | 已覆盖 |
| 20 | 杜绝漂移 | 🔴 | standards/doc.md 提及 check_docs.py | 缺专章：代码/文档/API/schema/版本漂移 |
| 21 | NearSlot 特色域 | ✅ | research/08（本次新增） | 时段预约内核已深化 |

## 2. 本轮新增强化

| 承载文档 | 覆盖维度 | 说明 |
|---|---|---|
| research/08-nearslot-domain-research.md | 6, 17, 21 | 特色域：时段建模/双重正确性/Hold/候补/时空约束 |
| research/09-picknear-verification.md | 8, 9, 10, 11 | PickNear 源码复核（CONFIRMED/CORRECTED/NEW） |
| research/10-backend-engineering-deep.md | 3, 9, 10, 11, 18, 19, 20 | 日志错误/版本同步/测试/模块化/防漂移 |
| research/11-frontend-ux-deep.md | 4, 12, 16, 17 | UX 标准/用户旅程/响应式/前端数据结构 |
| research/12-quality-governance-deep.md | 1, 2, 3, 14, 15, 20 | 需求覆盖/设计约束/评审验收/中文注释/防漂移 |

## 3. 交叉引用一致性检查（防漂移）

- 每份 new 文档 frontmatter 的 `source_of_truth` 指向 `docs/research/<本文件名>`（本仓库内），与既有 01-07 风格一致。
- docs/README.md 导航表须登记 08-12；根 README.md 完成状态表同步。
- 既有文档中的事实性错误已据源码复核修正：
  - orderId ≠ traceId（两次 nextId()）——ADR-007 / research/02 / research/07 已修正。
  - verifyUserLevel 仅校验 allowedLevels+minLevel，allowedCities 未生效——research/07 已修正。
  - rollback Lua 为 37 行——research/07 已修正。

## 4. 待办（Phase 1 落地，不在本轮）

- `scripts/docs/check_docs.py` 实现：frontmatter 必填、站内链接、source_of_truth 存在、current 从 README 可达。
- 版本号同步脚本：pom revision / frontend package.json / API 版本头 / DB migration 版本四者一致性校验。
- 用户旅程全量验收脚本（含 AI 分支、取消/候补分支、异常分支）。
