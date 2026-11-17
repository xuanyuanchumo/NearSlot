---
title: NearSlot 特色域研究：时段预约内核（非秒杀化设计）
status: current
source_of_truth:
  - ../../docs/research/08-nearslot-domain-research.md
updated: 2026-11-16
---

# 研究报告 08：NearSlot 特色域研究——时段预约内核

> **定位**：前七篇回答"从 PickNear / hmdp-plus 抄什么"，本篇回答"NearSlot 与它们根本不同之处该怎么设计"。
> **证据等级**：§1–§6 结论来自两项目源码实证 + 外部系统设计文献（已标注链接）；§7–§9 为 NearSlot 领域推导，标注为「设计提案，待压测/验收验证」。
> **基线**：两项目都是"预付券秒杀"（库存是数量）；NearSlot 是"时段预约"（库存是带时间坐标的稀缺资源）。把秒杀脚本直接搬过来会踩三类独有故障：重复预约（同一技师同一时刻两人）、支付中途时段被占、候补提升重复发放。

## 1. 秒杀 vs 时段预约：本质差异（决定不能照抄）

| 维度 | PickNear / hmdp-plus（券秒杀） | NearSlot（时段预约） | 后果 |
|---|---|---|---|
| 库存形态 | 标量余量 `stock:N` | 槽位 `(technicianId, date, window)`，容量常为 1 | 锁粒度从"商品"降到"槽位" |
| 正确性首要目标 | 不超卖（DECR≥0） | **不重复占用** + 不超卖 | 需要唯一约束兜底，不止 DECR |
| 库存有效期 | 券的 begin/end 是绝对时刻 | 槽位相对当地时区，涉 DST | 需时区存储 + RRULE 写时展开 |
| 持有窗口 | 抢到即异步落库，无"占而未付"态 | 抢到→支付 30–120s，需 **hold 过期释放** | 两项目均无此状态机 |
| 取消后的动作 | 回补余量 | 回补 + **择人提升候补** | 取消端点不能做重逻辑 |
| 用户维度 | 一人一券 | 一人一时段 + 技师时间不重叠 | 双维度冲突检测 |
| 时空约束 | 无 | GEO 半径 + 技师日历 + 上门行程时间 | NearSlot 独有领域 |

**结论**：继承两项目的"Lua 原子预扣 + MQ 异步落库（hmdp-plus 用 Kafka=源项目事实 → NearSlot 选 RabbitMQ，ADR-014） + 对账闭环"作为**骨架**；在骨架上叠加本篇的时段内核作为**差异层**。

## 2. 时段库存建模（NearSlot 选型：槽位行 + 唯一约束兜底）

外部系统设计的三种模型（[Let's Build Solutions, 2026-05-25](https://letsbuildsolutions.com/blog/system-design/designing-a-reservation-and-booking-system-inventory-holds-double-booking-prevention-and-concurrency-control/)）：

| 模型 | 存储 | 查询复杂度 | 适用 | NearSlot 判定 |
|---|---|---|---|---|
| 槽位行（slot-based） | 每个可预约单元一行，含 `status/version/held_by/booking_id` | 最低（主键等值） | 大多数系统 | **采用** |
| 开区间（interval） | (start, end) 存区间，查询时做区间运算 | 高（重叠检测难索引） | 排班稀疏 | 不采用（并发下区间算术是可靠性负债） |
| RRULE 规则 | RFC 5545 规则运行时展开 | 最高 | 复杂周期 | **写时展开为槽位行**，查询期绝不展开 |

槽位行表关键约束（DB 侧最后防线，无论用哪种锁）：

```sql
CONSTRAINT uq_slot_occupy UNIQUE (technician_id, starts_at)  -- 防同技师同时刻重复占用
CONSTRAINT uq_user_slot   UNIQUE (user_id, slot_id)          -- 一人一时段兜底
status   VARCHAR  -- available | held | booked
version  INT      -- 乐观锁版本号
```

- 并发策略：**乐观锁为primary path，唯一约束为硬兜底，冲突用指数退避 + 抖动重试**（同文献）。
- 高频热点槽位用 Redis Lua 前置预扣（继承 ADR-004），DB 唯一约束兜底——与两项目一致，但 KEYS 从"券库存/用户集合"换成"槽位余量/用户槽位集合/技师时间轴"。

## 3. 双重正确性：防超卖 ≠ 防重复预约

两项目只解决"余量不超卖"（DECR + SISMEMBER），NearSlot 必须同时保证：

1. **余量维度**：`slot:avail:{slotId}` DECR≥0（容量>1 的团体时段）。
2. **用户维度**：`slot:booked:{slotId}` SISMEMBER 一人一时段（继承 Set 手法）。
3. **技师时间维度（新增）**：技师在 `[start, end)` 不可被两单占用——用 **ZSet 时间轴 + 重叠检测**（score=startsAt，value=endsAt），Lua 内 `ZRANGEBYSCORE` 查 `start < newEnd AND end > newStart` 冲突集。

| 检查 | Redis 结构 | 两项目出处 | NearSlot 状态 |
|---|---|---|---|
| 余量 | String DECR | 双方 | 继承 |
| 一人一时段 | Set SISMEMBER | 双方 | 继承 |
| 技师时间不重叠 | ZSet 时间轴 + Lua 重叠查询 | **无（新增）** | NearSlot 特色 |

## 4. Hold-Then-Confirm：两段式预约（NearSlot 全新状态机）

两项目"抢到即发 MQ 落库"，没有"占而未付"中间态。预约场景用户支付需 30–120s，必须引入一等公民 Hold 实体（同文献）：

```
抢约:  Lua(slot:available DECR + 用户Set + 技师时间轴占位 + trace日志, holdId)
      → Redis hold 记录(status=active, expiresAt=now+10min, 必须 > 支付P99)
      → 返回 holdId，前端进入支付
支付成功: 状态机 active→booked（乐观锁 version+1），hold→converted，清 hold TTL
超时未付: 后台扫描任务（30–60s 周期，非同步路径）→ 释放 hold → 槽位回补 → 触发候补提升
```

要点（继承 + 新增）：
- **Hold 过期必须后台任务处理**，禁止放在抢约同步链路（文献明确：同步清理会把取消端点变慢且耦合）。NearSlot 用 Redisson 延迟队列（ADR-009）投递 `hold.expire` 消息，消费端幂等（RepeatExecuteLimit(holdId)）。
- **回补反转语义**（ADR-005）同样适用于 hold 超时：DECR 的逆向必须携带方向参数，trace 日志写 `RESTORE/HOLD_TIMEOUT`。
- 支付回调（FR-4.2）与 hold 过期存在竞态：支付回调先校验 `hold.status==active`，否则按"已过期回补但支付已扣款"走退款补偿链——**NearSlot 新增的补偿分支**，两项目无对应场景。

## 5. 候补提升：异步、exactly-once、公平排序

hmdp-plus 的订阅通知（ZSet + autoIssue）是雏形，但缺少"通知→认领→过期→再提升"的完整 offer 状态机。外部 waitlist 设计（[Algoroq, 2025-01-15](https://algoroq.io/system-design/design-waitlist-system/)）给出可借鉴要点：

| 机制 | 文献做法 | NearSlot 落地（融合 hmdp-plus） |
|---|---|---|
| 公平排序 | score = 时间戳×1000 + 随机抖动（同毫秒公平） | ZSet score=`tsMs*1000+jitter`，避免网络邻近用户插队 |
| 原子入队 | Lua: 已存在→返回原序位；否则 ZADD 返回 ZRANK | 继承 Lua 原子，幂等键 userId+slotId |
| 提升触发 | RabbitMQ slot-opened 事件 + SETNX 抢占提升权 | 取消/回补发 `slot.opened` 事件，消费者用锁抢占（防多实例重复提升） |
| exactly-once | 锁 + DB offer 表唯一约束 `(slot_id,user_id)` 兜底 | 继承：Redis 锁 + offer 路由表唯一约束 |
| 认领窗口 | offer expiresAt，TTL 任务扫描，过期再提升下一位 | hold 复用 §4 的 10min 窗口 + 延迟队列 |
| 位次查询 | 前 100 名 WebSocket 实时，其余 30s 缓存轮询 | 前端标准（research/05）已有 SSE，位次走 SSE 推送 |

**取消端点只做"标记可用 + 发 slot.opened 事件"**，提升逻辑全在异步消费者——这是 NearSlot 与 hmdp-plus 同步 autoIssue 的关键改进（文献明确：同步提升让取消变慢且失败重试耦合在关键路径）。

## 6. 取消与回补的衔接（nearSlot 特色序列）

```
取消请求 → 状态机校验(CONFIRMED→CANCELLED) → @Idempotent(cancelId)
  → Lua 回补(反转 beforeQty/afterQty, trace=RESTORE) + 技师时间轴移除 + 用户Set SREM
  → 对账日志(RESTORE/SUCCESS) → 发 slot.opened 事件（不再做提升）
  → 异步消费者: 锁抢占 → 取候补 ZRANGE 首位 → 创 offer(hold) → 通知(SSE/短信)
  → 若 offer 超时未认领 → 回补 → 再次发 slot.opened（循环直到队列空）
```

- **幂等**：取消与支付回调并发时，以 hold/order 状态机 + 乐观锁 version 裁决，禁止双向覆盖。
- **观察**：每次提升写 trace（offerId、slotId、userId、原因），与 §4 hold trace 同 `traceId` 串树（ADR-011 白名单内）。

## 7. 上门服务的时空约束（设计提案，待压测/验收验证）

两项目无任何时空约束；这是 NearSlot 最鲜明的领域特色：

| 约束 | 设计 | 关联 |
|---|---|---|
| GEO 准入 | 用户坐标 → `GEOSEARCH 5km` 筛服务商（FR-2.3） | 两项目 GEO 手法继承 |
| 技师日历 | 工作日/休息日/请假写入排班表，槽位生成时跳过 | 新增领域 |
| 上门行程时间 | 相邻预约间留 `travelBuffer`（如 30min），时间轴重叠检测时计入 | 新增领域，防"技师迟到" |
| 可变时长 | 服务项决定 duration，槽位长度不一 → 用 ZSet 时间轴而非定长分片 | 新增领域（§3） |
| 时区/DST | 槽位存 `startsAt(UTC instant) + tzId + localWallTime`，RRULE 写时展开 | 新增领域，防 DST 日重复/漏槽 |

> 证据缺口：行程缓冲与 DST 的正确性依赖真实排班数据，需在 Phase 1 用 Testcontainers + 固定时区的用例验证，不在本轮形成定量结论。

## 8. 爽约/风控（NearSlot 新增域，两项目空白）

| 场景 | 提案 | 验收关联 |
|---|---|---|
| 用户爽约（no-show） | 核销超时未到 → 标记 no-show → 扣保证金/计违约分 | FR-4 核销链路新增字段 |
| 频繁取消 | 24h 内取消>N 次 → 提升门槛或限流（复用 ratelimit SPI） | 复用 framework |
| 技师侧违约 | 技师临时取消 → 优先补偿用户（升级候补 + 优惠券） | 运营规则，待产品确认 |
| 黑名单 | 违约分超阈值 → 禁止抢约（Lua 前置检查） | 新增加入 Lua 校验项 |

## 9. 三级准入闸门（与 Agent 双闸口呼应）

NearSlot 抢约链在 Lua 之前串联三道领域闸门，任一失败即短路：

```
GEO 闸门(5km内?) → 等级闸门(UserInfo.level ≥ 门槛) → 时间闸门(槽位available + 技师无冲突)
```

- 与 ADR-010 的"工具双闸口"同构：**领域闸门保护库存，工具闸口保护权限**，两层不可互相替代。
- AI 助手自然语言预约（FR-5.1）必须走同一套闸门，禁止 Agent 绕过 GEO/等级直接调 Lua。

## 10. NearSlot 特色落地清单（可验收）

1. 槽位表加 `uq_slot_occupy / uq_user_slot` 唯一约束；乐观锁 + 唯一约束双保险（§2）。
2. 抢约 Lua 同时做余量/一人一时段/技师时间轴三检查，写 trace（§3）。
3. 引入 Hold 两段式 + 后台过期任务；支付/hold 竞态走补偿链（§4）。
4. 候补提升异步化：slot.opened 事件 + 锁抢占 + offer 表唯一约束 + jitter 公平排序（§5）。
5. 取消端点只标记 + 发事件，提升全在异步消费者（§6）。
6. 排班/行程缓冲/时区/DST/爽约风控进入 Phase 1 验收用例（§7–§8）。
7. 领域三闸门与 Agent 双闸口分层，Agent 不得绕过（§9）。

## 11. 证据来源与不确定性

- 两项目实证：`research/01` §3–5、`research/02` §3、`research/07` §1–5（源码级，已核对）。
- 外部系统设计文献：
  - [Designing a Reservation and Booking System — Let's Build Solutions (2026-05-25)](https://letsbuildsolutions.com/blog/system-design/designing-a-reservation-and-booking-system-inventory-holds-double-booking-prevention-and-concurrency-control/)：槽位建模、锁策略、hold-then-confirm、异步候补提升、时区。
  - [System Design: Waitlist System — Algoroq (2025-01-15)](https://algoroq.io/system-design/design-waitlist-system/)：公平排序抖动、exactly-once 提升、位次查询分层。
- 不确定性：外部文献为通用系统设计实践，非本仓库压测数据；§7–§8 为设计提案，量化阈值（行程缓冲、过期窗口、违约次数）须在 Phase 1 以真实数据和压测确定，引用时标注「未复测」。

—— 完 ——
