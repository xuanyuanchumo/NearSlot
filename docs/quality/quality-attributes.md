---
title: 质量属性与非功能需求（SLO）
status: current
source_of_truth:
  - ../../docs/quality/quality-attributes.md
updated: 2026-11-16
---

# 质量属性与非功能需求（SLO）

## 1. 质量属性目标表

| 属性 | 指标 | 目标 | 实证来源 |
|---|---|---|---|
| 性能 | 空接口 TPS | ≥5000（参考 5500） | PickNear 压测 |
| 性能 | 抢约链路 P99 | ≤200ms | 设计目标 |
| 性能 | 拦截器增量耗时 | 命中≤1ms / 未命中<5ms（>100ms 告警） | PickNear ↓50ms 基线；阈值统一口径 |
| 可用性 | 核心服务月度可用 | 99.9% | 运维门禁 |
| 一致性 | 抢约超卖 | 0（对账 ABNORMAL=0） | hmdp-plus 闭环 |
| 一致性 | 对账差异数 | 每日 0，出现即告警 | 对账任务 |
| 安全 | Token 主动失效成功率 | 100% | PickNear 实证 |
| 安全 | 高危工具越权次数 | 0 | PickNear 内网实证 |
| 可维护性 | 行覆盖率 | ≥80%（核心 ≥85%） | CI 门禁 |
| 可观测 | 关键链路 trace 覆盖 | 100%（白名单内） | Langfuse/Prometheus |
| 成本 | Agent 输入 token 增幅 | 多轮 ≤1.2×（O(1) 压缩） | PickNear ~57% 削减 |

## 2. 一致性与可靠性机制（指标背后的机制索引）

- 超卖=0：Lua 原子预扣 + 乐观锁 + 回补反转 + 对账任务（ADR-004/005）。
- 消息不丢：publisher-confirm + persistent + 手动 ack + 延迟门丢弃回补 + 死信队列 + 幂等标记（ADR-008/ADR-014）。
- 凭证不失控：Lua 原子族 + 两级版本校验 + 422 自愈（ADR-002）。
- 故障兜底：回滚失败表 + RollbackAlert 告警（hmdp-plus 模式）。

## 3. 监控与告警

- 指标：Prometheus（业务 counter：抢约成功/失败/回补、缓存命中率、限流触发、Guard 拦截、消费延迟）。
- 告警阈值：对账 ABNORMAL>0 立即；Lua>200ms；拦截器>100ms；主链>500ms；消费失败 counter 增长。
- 追踪：OTLP→Langfuse，白名单前缀（ADR-011）；session→subagent→tool_call→guard 同 traceId 串树。

## 4. 容量与扩展

- 起步容量：2 库 × 2 表支撑预约单千万级/年；Redis 单实例（sentinel 扩展期）。
- 扩展路径：app 水平扩（无状态）→ RabbitMQ 多队列 + 消费者并发扩 → 分片扩容迁移（路由表兼容窗口）。
