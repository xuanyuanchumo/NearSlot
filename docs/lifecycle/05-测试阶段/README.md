---
title: 测试阶段文档目录（索引 + 路由）
status: current
source_of_truth:
  - ../../../docs/lifecycle/05-测试阶段/README.md
updated: 2026-11-16
---

# 测试阶段文档目录（索引 + 路由）

> **结论先行**：本目录只承载「测试阶段文档索引 + 模板骨架」，**不是事实源**。测试策略/金字塔/覆盖率门禁以 [testing/strategy.md](../../testing/strategy.md) 为唯一事实源；11 项 SLO 以 [quality/quality-attributes.md](../../quality/quality-attributes.md) 为唯一事实源；G4 门禁以 [quality/review-system.md](../../quality/review-system.md) 为唯一事实源。模板只规定「怎么填」，填入的具体数值必须链接回 SSOT，禁止复制正文造成双源漂移。

## 1. 文档索引

| 文档 | 定位 | 状态 | 责任代理 | 关联 SSOT | 出口门禁 |
|---|---|---|---|---|---|
| [测试计划.md](测试计划.md) | 范围/策略/资源/排期/准入 | 模板·待填 | tester | strategy.md §1–4 | G4 |
| [测试用例文档.md](测试用例文档.md) | 用例库（含 8 个 NearSlot 样例） | 模板·含样例 | tester | strategy.md §2 + functional-requirements.md | G4 |
| [BUG描述报告书.md](BUG描述报告书.md) | 缺陷记录 + 状态机 + 反哺 | 模板·含样例 | tester | review-system.md §4 | G4 |
| [软件测试报告.md](软件测试报告.md) | 执行/覆盖率/必测/压测/SLO | 模板·待填 | tester | quality-attributes.md §1 + performance/guide.md §5 | G4→G6 |
| [集成测试报告.md](集成测试报告.md) | Testcontainers 集成结果 | 模板·待填 | tester | strategy.md §3 | G4 |
| [测试分析报告.md](测试分析报告.md) | 缺陷分布/趋势/根因/质量评估 | 模板·待填 | tester | review-system.md §4 | G4 |

> 状态图例：模板·待填 = 骨架，不得当作已交付证据；模板·含样例 = 附演示数据，正式执行须替换。

## 2. SSOT 链接表（禁止复制正文）

| 主题 | 权威文档（相对本目录） | 本目录引用方式 |
|---|---|---|
| 测试金字塔 70/20/10、覆盖率门禁、必测清单 | [../../testing/strategy.md](../../testing/strategy.md) | 计划/报告只写「按 strategy.md §1/§2 执行」，不重列占比 |
| 11 项 SLO 指标 | [../../quality/quality-attributes.md](../../quality/quality-attributes.md) | 报告 SLO 对照表逐行链接，指标取自 §1 |
| G4 测试评审、缺陷分级、反哺闭环 | [../../quality/review-system.md](../../quality/review-system.md) | 检查单对齐五维；反哺走 §4 |
| Lua 返回码单一枚举（防 §F.1 矛盾）、两级截断（§F.4） | [../../research/09-picknear-verification.md](../../research/09-picknear-verification.md) | 契约用例/缺陷根因引用，不重述源码行号 |
| orderId≠traceId、在途保护窗口 | [../../research/09b-hmdp-plus-verification.md](../../research/09b-hmdp-plus-verification.md) | 对账用例引用 |
| Hold 竞态、候补 exactly-once | [../../research/08-nearslot-domain-research.md](../../research/08-nearslot-domain-research.md) | 领域用例引用 |
| 错误码 501xx/401xx/429xx、X-Auth-Reason | [../../api/design.md](../../api/design.md) | 用例预期断言引用 |
| 压测条件六要素、性能红线 | [../../performance/guide.md](../../performance/guide.md) | 压测结果表列头取自 §5 |
| ADR-005 回补反转语义 | [../../architecture/decisions.md](../../architecture/decisions.md) | 回补用例/缺陷反哺引用 |
| 文档治理（frontmatter/一主题一 current） | [../../standards/doc.md](../../standards/doc.md) | 全部模板遵循 |

## 3. 任务 → 文档路由

| 你要做的事 | 先读（SSOT） | 再产出（本目录） |
|---|---|---|
| 立项测试、排期 | strategy.md §1–4 | 测试计划.md |
| 写用例、补必测项 | strategy.md §2 + functional-requirements.md | 测试用例文档.md |
| 发现缺陷、跟踪修复 | review-system.md §4 + api/design.md §3 | BUG描述报告书.md |
| 跑完一轮、出放行建议 | quality-attributes.md §1 + performance/guide.md §5 | 软件测试报告.md |
| 跑 Testcontainers 集成 | strategy.md §3 | 集成测试报告.md |
| 复盘质量、提改进 | review-system.md §4 | 测试分析报告.md |

## 4. 模板通用规范（全部文档适用）

每份文档必含：① frontmatter（title / status:current / source_of_truth:self / updated:2026-11-16）；② 文档信息表（编号/版本/状态/责任代理 tester/关联 FR 或 SLO/关联既有文档）；③ 正文结构（表格优先、结论前置、量化数字带条件）；④ 检查单（对齐门禁五维：功能/安全/性能/可维护/可观测）；⑤ 变更记录表。治理遵循 [../../standards/doc.md](../../standards/doc.md)；返回总索引见 [../README.md](../README.md)。

## 5. 变更记录

| 版本 | 日期 | 变更人 | 说明 | 关联 ADR/standards |
|---|---|---|---|---|
| v1.0 | 2026-11-16 | tester | 建目录，索引 + 6 模板，SSOT 链接全部指向既有文档 | — |
