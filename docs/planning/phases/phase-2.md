---
title: Phase 2 详细开发计划（业务核心 / G3+G4）
status: current
source_of_truth:
  - ../../../docs/planning/phases/phase-2.md
updated: 2026-11-16
---

# Phase 2 详细开发计划：业务核心（W3–5 / G3+G4）

> 总纲：[development-plan.md](../development-plan.md) · 任务：[tasks.md](../tasks.md) §3 · 特色域：[08](../../research/08-nearslot-domain-research.md) · 核验：[09](../../research/09-picknear-verification.md)/[09b](../../research/09b-hmdp-plus-verification.md)

## 1. 目标与前置

- **目标**：跑通"找服务商→抢约→Hold→支付→履约→对账→候补"主链，落地 NearSlot 特色时段内核。
- **前置**：G2 通过（framework 八能力可用）；槽位 Lua 依赖 Phase 1 锁/幂等。
- **出口**：G3 代码评审 + G4 测试评审（Lua 语义全绿）；并发 100 抢 1 恰好 1 成功；对账 ABNORMAL=0。

## 2. 具体开发步骤（D→C→I）

| 步 | 环 | 动作 | 工件 | 验收 |
|---|---|---|---|---|
| 1 | C | 先冻结槽位 Lua 返回码枚举与 trace 日志结构（BaseCode + JSON 字段） | 枚举类 + Lua 头契约 | 契约测试先红 |
| 2 | I | 双 Token 认证：4 键原子写 + 6 Lua + 双拦截器 + 422 自愈 + 防爆破 | auth 模块 | 改密旧 AT ≤1 请求失效；X-Auth-Reason 断言 |
| 3 | I | 服务商/技师/时段：GEOSEARCH 5km + BloomFilter + 时段发布 + 失效广播 | shop 模块 | GEO 与广播多实例生效 |
| 4 | C/I | 槽位抢约 Lua：余量 DECR + SISMEMBER 一人一时段 + 技师时间轴重叠 + trace（cjson） | `appointment.lua` + Domain | 并发断言 + 三检查语义 |
| 5 | I | Hold 两段式：状态机 + 延迟队列过期任务 + 支付/hold 竞态补偿链 | appointment 模块 | 回补反转（ADR-005）；竞态走补偿 |
| 6 | I | 订单闭环：路由表 + 对账日志 + 回滚失败表 + 支付回调幂等 | order 模块 | 重复回调不重复入账 |
| 7 | I | 候补异步提升：ZSet(jitter 排序) + slot.opened 事件 + 锁抢占 + offer 唯一约束 | appointment 模块 | exactly-once；取消端点不阻塞 |
| 8 | I | 抢约令牌前置闸：access token + compare-and-del Lua + 独立限流场景 | framework+appointment | 原子消费（09b §6） |
| 9 | I/V | 对账定时任务：扫描 PENDING→三方互证→在途保护窗口→回补/告警 | order 定时任务 | 对账 ABNORMAL=0；trace 反哺 |

## 3. 落地方案

- **Lua 原子层**：KEYS = 余量/用户集合/技师时间轴/trace；ARGV 含 orderId 与 traceId **分别生成**（[09b §5](../../research/09b-hmdp-plus-verification.md)，非同源）；用 `redis.call('TIME')` 服务器时钟；返回结构化 JSON。
- **回补反转**：回补 API 显式传方向，反转 beforeQty/afterQty（ADR-005）；回滚删键重载而非值回补（[08 §2](../../research/08-nearslot-domain-research.md)）。
- **消费模板**：继承 AbstractConsumerHandler 骨架——beforeConsume 延迟门>10s 丢弃回补 → doConsume 幂等+乐观锁 → after* 钩子（清候选/通知/Top 榜）；对账日志 best-effort 单独 try/catch。
- **失效广播**：RabbitMQ 每实例独占队列（匿名队列 + fanout 绑定，替代 hmdp-plus 的 groupId 随机 uuid 每实例广播）实现广播；@ServiceLock(Write) 串行化。
- **取消序列**：状态机校验→回补反转→发 slot.opened 事件；**提升全在异步消费者**（[08 §6](../../research/08-nearslot-domain-research.md)）。

## 4. 闭环方案

| 环 | 验证动作 | 通过标准 |
|---|---|---|
| V-1 | Testcontainers-Redis 跑真实 Lua：断言返回码/余量/一人一时段/技师重叠/trace 字段 | 语义正确；回补反转断言通过 |
| V-2 | 并发测试：100 线程抢 1 余量 | 恰好 1 成功、99 失败（50101/50102） |
| V-3 | Token 全生命周期：登录/刷新/422 自愈/改密 bump/顶替 | 失效 ≤1 请求内；X-Auth-Reason 正确 |
| V-4 | MQ 模板：延迟>10s 回补、3 次重试死信、幂等重放跳过 | 全通过 |
| V-5 | 对账：制造 Redis 有 trace 无 DB 单 | 在途窗口内跳过、超时回补、ABNORMAL=0 |
| V-6 | 候补提升重复消费 | 锁+唯一约束保证 exactly-once |
| F | 一致性缺陷 → 补 ADR 或修 research/08；同类两次 blocker 反哺 standards | 规范与代码同步 |

## 5. DoD Checklist

- [ ] 主链路 E2E 全绿（预约/取消/候补/支付）
- [ ] Lua 语义测试全绿，返回码无双口径
- [ ] 并发 100 抢 1 恰好 1 成功；对账 ABNORMAL=0
- [ ] Token 4 键 + 6 Lua + 422 自愈验证
- [ ] Hold 竞态补偿、取消异步提升验证
- [ ] G3+G4 通过；Blocker=0

## 6. 风险与回退

| 风险 | 缓解 |
|---|---|
| 一致性闭环缺陷最密集 | 预留 1 周缓冲；每步先契约后实现 |
| orderId/traceId 误用同源 | 契约测试锁定"两次取号" |
| Hold 过期与支付竞态 | 以状态机+乐观锁裁决，补偿链兜底 |
| 延迟队列丢消息 | 对账扫描兜底（Phase 1 已留位） |
