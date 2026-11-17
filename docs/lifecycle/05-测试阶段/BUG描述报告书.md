---
title: BUG描述报告书（模板 + 样例）
status: current
source_of_truth:
  - ../../../docs/lifecycle/05-测试阶段/BUG描述报告书.md
updated: 2026-11-16
---

# BUG描述报告书（模板 + 样例）

> **结论先行**：本报告只提供缺陷记录骨架与一份虚构样例；缺陷分级口径以 [review-system.md](../../quality/review-system.md) §3 为准，错误码含义以 [api/design.md](../../api/design.md) §3 为准。反哺（ standards/ADR ）必须走 review-system.md §4 闭环，不在本报告内改写规范。

## 1. 文档信息表

| 项目 | 内容 |
|---|---|
| 编号 | NS-BUG-YYYY-NNN（样例：NS-BUG-2026-001） |
| 版本 | v1.0（模板） |
| 状态 | 模板·含样例 |
| 责任代理 | tester |
| 关联 FR 或 SLO | 按缺陷填，如 FR-3.1 / SLO 抢约超卖=0 |
| 关联既有文档 | [strategy.md](../../testing/strategy.md) · [review-system.md](../../quality/review-system.md) · [api/design.md](../../api/design.md) · [decisions.md](../../architecture/decisions.md) |

## 2. 正文结构（模板字段）

| 字段 | 填写要求 |
|---|---|
| BUG 编号 | NS-BUG-YYYY-NNN，全局唯一，不复用 |
| 标题 | 一句话现象 + 影响，如「并发抢约 100 抢 1 出现 2 成功」 |
| 严重级别 | blocker（数据丢失/超卖/越权/崩溃）/ major（功能缺陷/性能红线）/ minor（UI/文案/边界） |
| 优先级 | P0–P3；blocker 默认 P0 |
| 发现阶段/门禁 | 单元/集成/E2E/压测；对应 G0–G6（本类多见 G4） |
| 环境 | 机器规格 / JDK 版本 / 构建版本 / 数据集（影子库），量化数字必带条件 |
| 前置条件 | 账号等级、时段余量、持仓状态等可复现前态 |
| 复现步骤 | 编号 1..n，逐步可照做，含并发工具与参数 |
| 预期结果 | 断言到具体错误码/DB 状态，引用 SSOT 条款 |
| 实际结果 | 观测值 + 错误码 / traceId / 日志片段 |
| 错误码/traceId/日志 | code（501xx/401xx/429xx）、X-Auth-Reason、traceId、关键日志行（脱敏） |
| 截图/附件 | 路径或链接；敏感数据脱敏 |
| 根因分析 | 定位到类/行；标注是否命中已知核验结论（如 09 §F.1、09b §1） |
| 修复建议 | 具体方案 + 是否需补契约测试 |
| 责任人 | 分派到的 developer / 模块 owner |
| 状态机 | 新建 → 已分派 → 修复中 → 待验证 → 关闭 / 拒绝 / 延期 |
| 验证记录 | 验证人/轮次/步骤/结果，回归用例 ID |
| 反哺 | 是否触发 standards/ 更新或新增 ADR（是 → 链接；否 → 说明） |

### 状态机

| 当前状态 | 允许的下一状态 | 触发条件 |
|---|---|---|
| 新建 | 已分派 / 拒绝 | tester 分派 / 判定非缺陷 |
| 已分派 | 修复中 / 延期 | developer 接手 / 排期外 |
| 修复中 | 待验证 | 提交修复并出包 |
| 待验证 | 关闭 / 修复中 | tester 回归通过 / 未通过打回 |
| 延期 | 修复中 | 条件满足重新排期 |

## 3. 填写样例（虚构，仅演示格式）

| 字段 | 内容 |
|---|---|
| BUG 编号 | NS-BUG-2026-001 |
| 标题 | 并发抢约 100 请求抢 1 个余量，出现 2 个成功预约 |
| 严重级别 | blocker |
| 优先级 | P0 |
| 发现阶段/门禁 | 压测阶段 / G4 |
| 环境 | 机器 8C16G 单节点；JDK 17.0.9；构建 v0.1.0-SNAPSHOT；影子库 slot 余量=1（数据见用例 NS-TC-APPT-001） |
| 前置条件 | 100 个不同用户均达等级门槛；同一槽位 slotId=9001 余量=1；GEO 5km 内 |
| 复现步骤 | 1. 用 wrk 100 线程同时 POST /api/v1/appointments（body 含 slotId=9001，各带唯一用户 Token）→ 2. 统计 200+code=200 的响应数 → 3. 查 tb_appointment 与槽位余量 |
| 预期结果 | 恰好 1 成功、99 失败（50101/50102），DB 仅 1 条、余量=0、对账 ABNORMAL=0（FR-3.1 验收标准） |
| 实际结果 | 2 个响应 code=200 且生成 2 条订单，其中一条技师时间轴与既有订单重叠；余量被扣为 -1 |
| 错误码/traceId/日志 | code=200（误判）；traceId=6f1c…a92；日志「Lua deduct ok orderId=…」出现两次，缺方向参数 |
| 截图/附件 | 压测汇总 CSV、Redis 余量截图（已脱敏） |
| 根因分析 | 抢约 Lua 仅做 DECR≥0 与用户 Set 判定，漏技师时间轴重叠检测；并发下两请求通过余量检查后各自落库，DB 唯一约束 uq_slot_occupy 在数据影子库未启用。命中 [08-nearslot-domain-research.md](../../research/08-nearslot-domain-research.md) §3「防超卖≠防重复预约」 |
| 修复建议 | 抢约 Lua 增加 ZRANGEBYSCORE 技师时间轴重叠检查；启用 uq_slot_occupy / uq_user_slot 唯一约束；补 NS-TC-APPT-001/004 回归，orderId 与 traceId 分别取号不假设同源（[09b](../../research/09b-hmdp-plus-verification.md) §5） |
| 责任人 | developer（appointment 模块） |
| 状态机 | 新建 → 已分派 → 修复中 → 待验证 → 关闭（本样例为演示终态） |
| 验证记录 | 第 1 轮修复后回归 NS-TC-APPT-001/004：100 并发仅 1 成功，ABNORMAL=0，通过 |
| 反哺 | 是 → 触发 [decisions.md](../../architecture/decisions.md) 补记「技师时间轴重叠为硬约束」；同步 [strategy.md](../../testing/strategy.md) §2 必测清单，经 [review-system.md](../../quality/review-system.md) §4 校验 |

## 4. 检查单（对齐 G4 五维）

| 维度 | 检查项 | 通过 |
|---|---|---|
| 功能正确 | 预期/实际断言到错误码与 DB 状态；复现步骤可照做 | ☐ |
| 安全 | token/traceId/日志已脱敏；越权类缺陷标注 X-Auth-Reason | ☐ |
| 性能 | 压测类缺陷记录机器/线程/时长/数据量/拓扑/JDK（perf §5） | ☐ |
| 可维护 | 关联 FR/SLO/ADR；反哺闭环已登记 | ☐ |
| 可观测 | 附 traceId 与关键日志片段，白名单内（ADR-011） | ☐ |

## 5. 变更记录

| 版本 | 日期 | 变更人 | 说明 | 关联 ADR/standards |
|---|---|---|---|---|
| v1.0 | 2026-11-16 | tester | 建模板 + 虚构样例 | — |
