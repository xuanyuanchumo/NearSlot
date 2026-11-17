---
title: 数据库设计规范
status: current
source_of_truth:
  - ../../docs/database/schema.md
updated: 2026-09-05
---

# 数据库设计规范

## 1. 建表规范

- 主键：BIGINT 雪花 ID（应用侧生成，ADR-007），禁止自增；分片键必须包含在主键语义中。
- 字符集 utf8mb4 / utf8mb4_0900_ai_ci；引擎 InnoDB。
- 每表必带：created_at DATETIME、updated_at DATETIME（应用侧填充）；逻辑删除 deleted TINYINT 0/1（业务表）。
- 状态字段 TINYINT + 枚举类（org.javaup enums 模式），禁止魔法数字散落。
- 索引命名 idx_{table}_{cols} / uk_{table}_{cols}；唯一约束优先唯一索引而非代码判重。
- 金额 DECIMAL(10,2)；时间戳 BIGINT（Feed/排行 score）；JSON 字段仅作扩展属性，不参与查询条件。

## 2. 分片策略（ADR-006）

- 2 库 × 2 表起步：ds0/ds1（id MOD 2），表名后缀 _0/_1（id 整除 2 再 MOD 2）。
- 库内自带路由表：appointment_order_router(order_id → ds/table)，订单回查零二次计算（hmdp-plus 模式）。
- 对账日志与订单同分片键（appointment_order_reconcile_log），保证库内 JOIN。
- 用户手机号独立表 HASH_MOD（等值查询专用），支持手机号直查。
- 配置全在 shardingsphere.yaml，Java 侧零分片代码；snakeyaml Representer 覆写类照搬 hmdp-sharding（1 文件）。

## 3. 核心表清单（v1）

| 表 | 说明 | 分片 |
|---|---|---|
| user / user_info / user_phone | 账号/档案/手机号 | 是(3) |
| shop / technician / service_item | 服务商/技师/服务项 | 否（量小） |
| time_slot / time_slot_stock | 时段定义 / 时段余量（Redis 主、DB 兜底） | 否 |
| appointment_order | 预约单（状态机 PENDING/CONFIRMED/CANCELLED/DONE） | 是 |
| appointment_order_router | 订单路由表（order_id, ds, table, voucher_ref） | 与订单同 |
| appointment_reconcile_log | 对账日志（BOOK/RESTORE × SUCCESS/FAIL） | 与订单同 |
| rollback_failure_log | 回滚失败兜底表（人工介入） | 是 |
| reminder_queue_log | 延迟提醒/订阅通知记录（去重窗口） | 否 |
| agent_conversation / agent_message | AI 会话/回合（(conversation_id,user_id) 归属校验） | 否 |

## 4. Redis 键规范（utils/RedisConstants 单文件收口）

- 命名：nearslot:{domain}:{biz}:{id}，全小写冒号分层；TTL 必须显式设置（除 trace 日志按业务）。
- 核心键：slot:stock:{slotId}（余量 String）、slot:user:{slotId}（已约 Set）、slot:trace:{slotId}（Hash traceId→日志）、candidate:{slotId}（候补 ZSet）、tech:rank:{shopId}:{yyyyMMdd}（Top 榜 ZSet TTL 90d）、cache:shop:{id}（TTL 30min+Jitter）、cache:null:{id}（2min）、lock:{biz}:{id}（10s）、idem:{key}、login:token:access/refresh、token:version:valid/current（8d）。
- trace 日志条目 JSON：{logType,ts,orderId,traceId,userId,slotId,beforeQty,changeQty,afterQty}，对账任务依赖（ADR-004）。

## 5. 迁移与变更

- SQL 脚本 sql/1_create_database.sql + N_business.sql（幂等：CREATE TABLE IF NOT EXISTS + 列变更走独立编号脚本）。
- 破坏性列变更需双写窗口说明（扩列→回填→切读→删旧列）。
- schema.md 与代码 entity 同步更新；PR 必须含 SQL 变更对应说明。
