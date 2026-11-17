---
title: 功能性需求（v1 范围）
status: current
source_of_truth:
  - ../../docs/quality/functional-requirements.md
updated: 2026-11-16
---

# 功能性需求（v1 范围）

## 1. 业务域与需求清单

### FR-1 用户与认证（auth）
- FR-1.1 手机验证码登录（60s 频控、GET+DEL 原子消费）。
- FR-1.2 密码登录（BCrypt、IP/账号防爆破）。
- FR-1.3 双 Token 自动续期（AT 30min / RT 7d / 并发自愈 422）。
- FR-1.4 改密踢出（bump version）与会员等级体系（Lv1–10，预约门槛校验）。

### FR-2 服务与时段（shop）
- FR-2.1 服务商/技师/服务项管理（BloomFilter 防穿透）。
- FR-2.2 时段发布与余量管理（Redis 主、DB 兜底、失效广播）。
- FR-2.3 GEO 附近服务商（GEOSEARCH 5km + 距离排序）。

### FR-3 抢约与候补（appointment）
- FR-3.1 在线抢约（Lua 原子预扣 + 一人一时段一条 + RabbitMQ 异步落库）。
- FR-3.2 候补队列（ZSet 余量回流通知 + 等级过滤）。
- FR-3.3 取消预约（状态机 + Redis 回补反转 + 对账日志）。
- FR-3.4 开抢前 2 分钟延迟提醒（Redisson 延迟队列 + 去重窗口 300s）。

### FR-4 订单与支付（order）
- FR-4.1 订单查询（路由表直查）与状态机（PENDING→CONFIRMED→DONE/CANCELLED）。
- FR-4.2 支付回调（幂等 + 对账）。
- FR-4.3 每日热门技师榜（ZSet 日键 TTL 90d）。

### FR-5 AI 助手（agent）
- FR-5.1 自然语言预约/查询（两阶段规划 + 工具调用 ≤5 轮）。
- FR-5.2 SSE 流式对话（meta/plan/progress/error/confirm）+ 敏感操作二次确认。
- FR-5.3 会话历史与压缩回放（agent_conversation/agent_message）。

## 2. 验收标准（抽样示例，全量随里程碑细化）

- FR-3.1：并发 100 同抢 1 余量 → 恰好 1 成功 99 失败（50101/50102）；DB/Redis 对账 ABNORMAL=0。
- FR-1.3：改密后旧 AT ≤1 请求内失效（401+X-Auth-Reason: superseded）。
- FR-5.2：删除类指令被 BLOCK 并给出理由；确认类指令进入 confirm 流程，TTL 300s。

## 3. 用户旅程（验收脚本基准）

浏览服务商→选时段→登录→抢约（成功/候补分支）→支付→提醒→完成→评价；AI 分支：自然语言下单→确认→SSE 反馈。
