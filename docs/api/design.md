---
title: API 设计规范
status: current
source_of_truth:
  - ../../docs/api/design.md
updated: 2026-09-05
---

# API 设计规范

## 1. REST 约定

- 资源名词复数、动作小写连字符：/appointments、/shops/{shopId}/technicians。
- 版本进路径：/api/v1/**（网关层可再映射）。
- 方法语义：GET 幂等只读；POST 创建/触发；PUT 全量更新；PATCH 局部更新；DELETE 需二次确认头部 X-Confirm: true（高危操作沿用 PickNear Guard 思想）。
- 状态码只用 200/201/204/400/401/403/404/409/429/500；业务失败统一 200 + code 区分（对齐两项目 Result 风格，避免前端双重判定）。

## 2. 统一响应体

```json
{ "code": 200, "message": "ok", "data": { }, "timestamp": 1730000000000 }
```

- code=200 成功；401xx 认证；403xx 权限；404xx 资源；429xx 限流/幂等；500xx 系统；501xx 业务。
- message 面向用户可直显（中文）；排查信息放 traceId，不进 message。
- data 中 Long 一律字符串化（jackson WRITE_NUMBERS_AS_STRINGS，ADR-012）。

## 3. 错误码表（业务段 501xx）

| code | 含义 | HTTP |
|---|---|---|
| 50101 | 库存/余量不足 | 200 |
| 50102 | 重复预约（一人一时段一条） | 200 |
| 50103 | 时段已满/已关闭 | 200 |
| 50104 | 会员等级不满足 | 200 |
| 50105 | 候补队列已满 | 200 |
| 40101 | 未登录 / 会话已失效 | 401 |
| 40102 | 凭证已被顶替（version 不一致） | 401 |
| 42901 | 触发限流 | 429 |
| 42902 | 幂等拦截（重复提交） | 429 |

401 响应必须携带 X-Auth-Reason: superseded | no-session（PickNear 约定，前端据此决定静默刷新或跳登录）。

## 4. 鉴权头

- 请求：authorization: Bearer <AT>（小写头名，值必须带 Bearer 前缀，禁止 Bearer null）；Refresh-Token 头优先、HttpOnly Cookie 兜底。
- 响应：X-Token-Refresh: ok|skipped|failed；刷新成功时 authorization 头携带新 AT，Set-Cookie 携带新 RT（SameSite=Lax / HTTPS 下 Secure+None）。
- 刷新失败透传 X-Auth-Reason，禁止前端无限重试与重复弹窗。

## 5. 幂等与限流

- 写接口默认 @Idempotent（key=SpEL 业务键，ttl 默认 10s，冲突返回 42902）；客户端可携带 Idempotency-Key 头参与 key。
- 限流响应头：X-RateLimit-Limit / X-RateLimit-Remaining / X-RateLimit-Reset；42901 时 Retry-After 必填。
- 秒杀/抢约接口禁止网关重试（retry 策略 0），避免重复扣减。

## 6. 分页与排序

- 列表默认 cursor 游标：?cursor=&limit=20（Feed/排行用 score 游标，对齐 hmdp-plus ScrollResult 滚动分页）。
- 排序字段走白名单（PickNear SORTABLE_FIELDS 模式），非法字段 400，禁止拼接 SQL。

## 7. SSE（AI 对话）

- 事件类型：meta（会话/回合信息）、plan（规划结果）、progress（工具执行进度）、error（可恢复错误）、confirm（需用户确认，带 confirmId）。
- confirm 暂停后通过 POST /agent/conversations/{id}/confirm 恢复；断线重连携带 Last-Event-ID 增量补发。
- data: 必须可 JSON 化；事件名与 PickNear 对齐以便前端复用解析器。

## 7.5 端点清单（v1，与 openapi.json 同步）

| 端点 | 方法 | 用途 | 需鉴权 |
|---|---|---|---|
| `/api/v1/auth/code` | POST | 下发验证码（dev 回显，生产接短信网关） | ❌ |
| `/api/v1/auth/login` | POST | 密码/验证码登录，返回 AT | ❌ |
| `/api/v1/auth/refresh` | POST | 旧 RT 换新 AT（并发自愈） | ❌（凭 RT） |
| `/api/v1/auth/logout` | POST | 清理四键，旧 AT 立即失效 | ✅ |
| `/api/v1/shops/{id}` | GET | 服务商详情（缓存三防） | ❌ |
| `/api/v1/shops/nearby` | GET | GEOSEARCH 附近检索 | ❌ |
| `/api/v1/slots` | POST | 发布时段（落库+预热+广播失效） | ✅ |
| `/api/v1/slots/{id}/close` | POST | 关闭时段 | ✅ |
| `/api/v1/appointments` | POST | 抢约（Lua 三检查） | ✅ |
| `/api/v1/appointments` | GET | 我的订单列表 | ✅ |
| `/api/v1/appointments/{id}` | GET | 订单详情（归属校验） | ✅ |
| `/api/v1/appointments/{id}/cancel` | POST | 取消（回补反转） | ✅ |
| `/api/v1/appointments/{slotId}/waitlist` | POST/DELETE | 加入/退出候补 | ✅ |
| `/api/v1/payments/callback` | POST | 支付回调（幂等） | ❌（网关签名待补） |
| `/api/v1/internal/reconcile/run` | POST | 手动触发对账 | 🔑 X-Admin-Token |

## 8. 文档与契约

- 接口定义以 springdoc(knife4j) 注解为唯一事实源，生成 openapi.json 入库；api/design.md 只承载规范不承载明细。
- 字段命名 camelCase；时间戳毫秒；枚举传输用 code（int）+ 文案由前端字典翻译。
- 破坏性变更必须升 /api/v2 并在 CHANGELOG 登记。
