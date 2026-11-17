---
title: 数据库选型研究：MySQL vs PostgreSQL（含"用 PG 替代 Redis/RabbitMQ"可行性评估）
status: current
source_of_truth:
  - 14-database-selection-research.md
updated: 2026-11-17
---

# 数据库选型研究：MySQL vs PostgreSQL

> **触发**：业界出现"用 PostgreSQL 统一替代 Redis / RabbitMQ / Elasticsearch"的主张（如 51CTO 相关文章）。
> **结论前置**：
> 1. **本项目不建议用 PG 替代 Redis**——抢约核心依赖单次 Lua 脚本内的多结构原子操作，PG 无等价能力。
> 2. **RabbitMQ 可被 PG 部分替代，但当前收益为负**——现有实现已稳定，替换反而增加改造面。
> 3. **MySQL 与 PostgreSQL 相比，PG 技术指标更优，但本项目切换成本高于收益**；建议**维持 MySQL**，把 PG 列为扩展期备选（另立 ADR 时评估）。
> 4. **协同使用（MySQL 主库 + PG 检索/分析）在本项目当前规模下不成立**——会引入双写一致性问题。

## 1. 先量化本项目真实负载（决策依据）

| 维度 | 实测/约定值 | 来源 |
|---|---|---|
| 抢约链路 P99 | ≤200ms | [quality-attributes.md §1](../quality/quality-attributes.md) |
| 空接口 TPS | ≥5000 | 同上 |
| 抢约核心操作 | 单 Lua 内 5 类结构操作 | [appointmentBook.lua](../../nearslot-module-appointment/src/main/resources/lua/appointmentBook.lua) |
| 预估日订单量 | 万级（2 库×2 表支撑千万级/年） | [database/schema.md](../database/schema.md) |
| 团队规模 | 1–3 人 | [decisions.md ADR-001](../architecture/decisions.md) |

**抢约 Lua 的实际操作**（这是判定的关键）：
```lua
SISMEMBER userKey userId      -- 一人一时段校验
ZRANGEBYSCORE timelineKey ... -- 技师时间轴重叠校验
DECR stockKey                 -- 余量扣减
SADD userKey userId           -- 落标记
ZADD timelineKey ...          -- 落时间轴
HSET traceKey traceId entry   -- 写审计日志
```
**六步在单次网络往返内原子完成。**

## 2. 「PG 替代 Redis」可行性逐项评估

| Redis 用途 | PG 替代方案 | 可行性 | 判定 |
|---|---|---|---|
| **抢约 Lua 原子预扣** | 存储过程 + 行锁 / SERIALIZABLE 事务 | ❌ **不可行** | 六类结构跨 4 张表，PG 需多表 JOIN + 多行锁，P99 无法保 200ms |
| 分布式锁（Redisson） | `pg_advisory_lock` | 🟡 可行但有代价 | advisory lock 占用连接，连接池压力大；Redisson 有看门狗自动续期，PG 需自实现 |
| 限流（RRateLimiter） | 表 + 时间窗口 | 🟡 可行但更慢 | 每秒 5000 次 UPDATE 会成写热点 |
| 延迟队列（RDelayedQueue） | 队列表 + `FOR UPDATE SKIP LOCKED` | ✅ 可行 | 本项目延迟量小（Hold 超时 + 提醒），完全够用 |
| 缓存 L2 | UNLOGGED TABLE + JSONB + 触发器 | ✅ 可行 | 但不如 Redis 快，且需自管 TTL 清理 |
| Token 四键 | 表 + 索引 | 🟡 可行但更慢 | 每次请求都校验，高频读；PG 有连接与 vacuum 压力 |
| 候补 ZSet | 表 + `ORDER BY score` | ✅ 可行 | 队列量小 |

**结论**：**Redis 不能被替代**。核心原因是抢约路径的"多结构单次原子"，这是 Redis Lua 的独有能力；PG 只能靠多行锁模拟，性能不达标。

> 反驳性验证：文章提到"PG 出队 0.3ms vs Redis 0.1ms"——这只针对**单表单行**的简单队列。本项目是 4 张表 + 6 类结构的复合判断，差距会放大一个数量级以上。

## 3. 「PG 替代 RabbitMQ」可行性评估

| RabbitMQ 用途 | PG 替代方案 | 可行性 | 本项目判定 |
|---|---|---|---|
| **抢约异步落库**（削峰 + 延迟门 + 重试计数 + 死信） | 队列表 + SKIP LOCKED + 自实现重试/DLQ | 🟡 可行 | 需重写 `AbstractRabbitConsumer` 全套语义，**改造面大、收益为负** |
| **缓存失效广播**（fanout 每实例） | `LISTEN/NOTIFY` | ✅ 可行 | 通知丢失可接受（有 TTL 兜底），是最适合替换的一处 |
| 待支付超时（延迟队列） | 队列表 + 定时扫描 | ✅ 可行 | 已用 Redisson 延迟队列，无需改 |

**关键限制**：`LISTEN/NOTIFY` **不持久、不重放**——连接断开期间的通知永久丢失。因此它**只能**用于可容忍丢失的广播（如缓存失效），**不能**用于订单落库这类必须可靠投递的场景。

## 4. MySQL vs PostgreSQL 逐项对比（本项目视角）

| 维度 | MySQL 8.0 | PostgreSQL 16 | 本项目权重 | 更优 |
|---|---|---|---|---|
| 与 ShardingSphere 5.3.2 配合成熟度 | **高**（hmdp-plus 已实证 2 库×2 表） | 中 | ★★★★★ | **MySQL** |
| 现有 DDL/实体/迁移脚本 | **已全部就绪** | 需全量重写 | ★★★★★ | **MySQL** |
| 团队熟悉度 + 运维经验 | 高 | 中 | ★★★★ | **MySQL** |
| JSONB（灵活属性，如时段扩展字段） | JSON 类型较弱（无 GIN 索引） | **JSONB + GIN 强** | ★★ | PG |
| 全文检索（服务商/服务项搜索） | 有 ngram，但弱 | **tsvector + GIN 强** | ★★ | PG |
| 并发写（MVCC 无读锁） | 有间隙锁，热点行易锁等待 | **无读锁，写并发更好** | ★★★ | PG |
| advisory lock（可替代部分 Redisson） | 有 `GET_LOCK`（较弱） | **`pg_advisory_lock` 更强** | ★★ | PG |
| LISTEN/NOTIFY | ❌ 无 | ✅ 有 | ★★ | PG |
| 分区分表生态 | 成熟 | 成熟（声明式分区更好用） | ★★ | 平 |
| 本次镜像获取 | **Docker Hub 拉取缓慢**（实证） | 未测 | ★ | — |

**加权结论**：本项目当前阶段的权重集中在**"与既有实现和 ShardingSphere 的契合度"**，MySQL 明显胜出（9 星 vs 4 星权重项全在 MySQL 侧）。

## 5. 三版方案

### 方案 A：维持 MySQL（**推荐**）
- 现状：MySQL 8.0 单库，ShardingSphere 做 2 库×2 表分片（Phase 5 启用）。
- 代价：放弃 PG 的 JSONB/全文检索/advisory lock 优势。
- 收益：零改造风险；分片方案已有 hmdp-plus 实证。
- **二阶影响**：若未来需要全文检索，可加 Elasticsearch 或 PG 从库，而非整体换库。

### 方案 B：切换 PostgreSQL
- 做法：DDL 全量重写、MyBatis-Plus 换 PG 方言、ShardingSphere 兼容性验证、全部集成测试重跑。
- 代价：**高**（估计 3–5 天），且 ShardingSphere + PG 缺少本项目的实证。
- 收益：获得 JSONB、全文检索、LISTEN/NOTIFY、更强 advisory lock。
- **二阶影响**：可顺势用 LISTEN/NOTIFY 替掉 RabbitMQ 的失效广播，**组件数从 4 降到 3**（符合文章主张）。

### 方案 C：MySQL + PG 协同（MySQL 主库 + PG 检索）
- 做法：订单等事务数据在 MySQL；服务商/服务项的全文检索与 GEO 分析放 PG。
- 代价：**双写一致性**（需 CDC 如 Debezium 或应用双写），运维复杂度上升。
- **判定**：项目当前规模（万级日订单）**完全不成立**——引入的双写一致性问题远超收益。

## 6. 需要现在做的事（不做过度设计）

| 项 | 动作 | 理由 |
|---|---|---|
| 数据库选型 | **维持 MySQL**，写入 ADR-019 | 已完成 |
| Redis | **不可替代**，保持现状 | 抢约原子性刚需 |
| RabbitMQ | 保留；**评估把"缓存失效广播"改为 LISTEN/NOTIFY 的收益** | 唯一真正适合替换点，但需先有 PG |
| PG 能力预留 | 在 `database/schema.md` 记录：若未来引入 PG，优先用于全文检索与 JSONB 属性 | 低成本预留 |

## 7. 证据来源

- 本项目：[quality-attributes.md](../quality/quality-attributes.md) SLO、[appointmentBook.lua](../../nearslot-module-appointment/src/main/resources/lua/appointmentBook.lua)、[ADR-014 RabbitMQ 选型](../architecture/decisions.md)
- 外部：[PostgreSQL LISTEN/NOTIFY: Real-Time Without the Message Broker](https://www.pedroalonso.net/blog/postgres-listen-notify-real-time/)、[I Replaced Redis with PostgreSQL](https://dev.to/polliog/i-replaced-redis-with-postgresql-and-its-faster-4942)
- **不确定性**：外部文章多为中小规模场景的定性结论；本项目 TPS≥5000 的定量验证需压测，当前**未复测**。
