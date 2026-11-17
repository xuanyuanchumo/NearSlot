---
title: Phase 2 出口门禁记录（G3 + G4）
status: current
source_of_truth:
  - phase2-exit.md
updated: 2026-11-17
---

# Phase 2 出口门禁记录

| 项 | 内容 |
|---|---|
| 阶段 | Phase 2（业务核心） |
| 结论 | **通过（自动检查全绿，人签待办）** |

## 1. 出口标准逐项对照

| 标准 | 结果 | 证据 |
|---|---|---|
| 并发 100 抢 1 余量 → 恰好 1 成功 99 失败 | ✅ | `AppointmentLuaIntegrationTest.concurrentBook_onlyOneSucceeds`（真实 Redis） |
| Lua 语义测试全绿（含回补反转 ADR-005） | ✅ | 5 个 Lua 集成测试全过 |
| 幂等/取消/超时分支测试通过 | ✅ | 幂等 42902、取消回补、Hold 超时延迟队列 |
| **对账 ABNORMAL=0** | ✅ | 实测日志：`对账完成：一致=1 异常=0 在途跳过=0` |
| 用户旅程端到端可用 | ✅ | 登录→抢约→取消→候补 全链路实测 |

## 2. 本轮新增/修复

| 类别 | 内容 |
|---|---|
| 新增 | `ReconciliationTask`：三方互证 + 在途保护窗口（12s） |
| 新增 | `ReconcileController`：内部运维接口，X-Admin-Token 鉴权 |
| 新增 | `ReconcileStatus` 枚举、对账测试 6 例 |
| 新增 | `sql/3_order_reconcile_status.sql` 迁移脚本 |
| 修复 | **模块边界违规**：对账任务原放 order 模块却引用 appointment 实体，违反 C-ARCH-01，已迁至 appointment |
| 修复 | DDL 未同步：新增 `reconcile_status` 列未加建表语句（实测被 H2 报"Column not found"） |
| 修复 | **限流器不自愈**：Redis 被 FLUSHALL 后 `RRateLimiter` 抛 "not initialized" 导致接口 500，改为捕获后自动重建并重试 |
| 修复 | 内部接口误被 LoginInterceptor 拦截返回 401，改为白名单 + 管理令牌 |
| 修复 | 403 段未映射 HTTP 状态（曾误返 200），补齐 403/404 映射 |
| 修复 | 对账日志未接入下单/取消流程，导致 ABNORMAL 永远无法归零 |

## 3. 测试现状

| 层 | 数量 |
|---|---|
| 单元 | 16（含对账 6、Lua 契约 3、时间轴 5、缓存/架构） |
| 集成（真实 Redis） | 10（抢约 5 + 候补 5） |
| E2E | 3 条链路（主链 / 候补 / 对账） |

## 4. 待办（非 Blocker）

- [ ] G3/G4 人签。
- [ ] 对账任务当前只标状态不自动回补；生产应加告警通道（Prometheus）。
- [ ] 对账周期 5 分钟固定，未做成配置项。
- [ ] `X-Admin-Token` 未纳入 .env.example 与 CI 密钥扫描白名单。

## 5. Blocker
无。
