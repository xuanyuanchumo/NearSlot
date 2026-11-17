---
title: 方案 A 垂直切片交付报告（2026-11-17）
status: current
source_of_truth:
  - vertical-slice-a-2026-11-17.md
updated: 2026-11-17
---

# 方案 A 垂直切片交付报告

> 执行依据：[提示词.md](../提示词.md) 十原则 · [审计报告](audit-2026-11-17.md) 方案 A。
> **核心成果**：全链路 `登录 → 查服务商 → 抢约 → 取消回补` 已端到端跑通，并在过程中发现并修复 **6 个真实缺陷**——这些缺陷在纯文档阶段完全不可见。

## 1. 端到端实测结果（真实 Redis + H2）

```
=== 1) 登录 ===
{"code":200,"data":{"accessToken":"eyJ...","userId":"1001","level":5},"message":"ok"}
TOKEN_LEN=145

=== 2) 抢约 ===
{"code":200,"data":1794890090174,"message":"ok"}
ORDER=1794890090174

=== 3) 重复抢约（幂等拦截）===
{"code":42902,"message":"重复提交"}

=== 4) 取消（回补）===
{"code":200,"data":null,"message":"ok"}
```

## 2. 发现并修复的 6 个真实缺陷（按发现顺序）

| # | 缺陷 | 根因 | 修复 | 严重度 |
|---|---|---|---|---|
| 1 | 抢约全部静默失败 | Lua 返回 `changeQty`，Java `BookResult` 只有 `deductQty`，Jackson 反序列化抛异常被吞 | `@JsonAlias("changeQty")` + `@JsonIgnoreProperties(ignoreUnknown)` | **Blocker** |
| 2 | 并发抢约 99 个返回 50103 而非 10005 | 技师时间轴 ZSet member 仅存 endsAt，同 slot 并发被误判为"时段被占" | member 改为 `slotId#endsAt`，同 slot 跳过重叠判定 | **Blocker** |
| 3 | `/v3/api-docs` 返回 500 | 显式锁定 `swagger-annotations-jakarta:2.2.30` 与 Knife4j 传递的 `swagger-core 2.2.55` 冲突 | 移除显式版本，改由 starter 传递，业务模块按 2.2.55 对齐 | Major |
| 4 | 业务异常返回 HTTP 500 + Spring 默认错误体 | 缺全局异常处理器，`BaseException` 未被转换 | 新增 `GlobalExceptionHandler`（按码段映射状态，body 统一 Result） | Major |
| 5 | 登录接口 500 | Redisson `RRateLimiter` 未 init 即 `tryAcquire` | 新增 `RateLimitInitializer` 启动幂等注册场景阈值 | Major |
| 6 | `@Idempotent` 抛 NPE | 编译未开 `-parameters`，`getParameterNames()` 返回 null | 编译器加 `-parameters` + 切面 null 防御（arg0 兜底） | Major |

**另有 2 个环境/配置缺陷**：
- H2 缺表导致登录 500 → 新增 `verify-schema.sql` 自动初始化
- `user` 是 H2 保留字 → JDBC URL 加 `NON_KEYWORDS=USER`
- 时段余量未预热导致 10004 → 新增 `SlotStockPreloader`

## 3. 新增/修改的代码

| 类型 | 文件 | 说明 |
|---|---|---|
| 新增 | `auth/controller/AuthController.java` | 登录接口 |
| 新增 | `auth/service/LoginService.java` | 登录编排（限流+双 Token） |
| 新增 | `auth/dto/LoginRequest/LoginResponse.java` | DTO |
| 新增 | `app/GlobalExceptionHandler.java` | 统一异常 → 统一响应体 |
| 新增 | `framework/config/RateLimitInitializer.java` | 限流场景启动注册 |
| 新增 | `appointment/service/SlotStockPreloader.java` | 时段余量预热 |
| 新增 | `AppointmentLuaIntegrationTest.java` | **真实 Redis 集成测试（5 例）** |
| 新增 | `resources/verify-schema.sql` | H2 冒烟 schema + 种子数据 |
| 修改 | `lua/appointmentBook.lua` | 时间轴 member 带 slotId |
| 修改 | `lua/appointmentRollback.lua` | ZREM 结构对齐 |
| 修改 | `BookResult.java` | 契约兼容 changeQty |
| 修改 | `pom.xml` | `-parameters`、integration profile |
| 修改 | `IdempotentAspect.java` | null 防御 |

## 4. 测试现状

| 层 | 数量 | 说明 |
|---|---|---|
| 单元 | 11 | BookResult(3) / SlotOverlap(5) / Cache / ArchUnit |
| **集成（真实 Redis）** | **5** | 并发100抢1 / 一人一时段 / 技师重叠 / 回补反转 / trace 审计 |
| E2E | 1 条链路 | 登录→抢约→幂等→取消 |

集成测试运行方式：
```bash
# 起 redis 容器
docker run -d --name ns-verify-redis -p 46379:6379 redis:7-alpine --requirepass verify123
# 跑集成测试
mvn -P integration -pl nearslot-module-appointment test \
  -Dnearslot.test.redis.host=127.0.0.1 -Dnearslot.test.redis.port=46379 \
  -Dnearslot.test.redis.password=verify123
```

## 5. 明确未完成（不伪装为已完成）

- **BCrypt 密码校验**：当前为占位等值比较（`LoginService` 已标 TODO）
- **验证码登录**：`ConsumeVerifyCode.lua` 已写但无下发/校验链路
- **防爆破计数**：仅有场景限流，无 IP/账号双维度计数
- **RT 写 HttpOnly Cookie**：当前仅返回 AT，RT 未落 Cookie
- **前端联调**：前端页面存在但未与后端真实对接验证
- **AI 模块**：0 代码（方案 A 明确延后）

## 6. 遗留风险

1. `LoginService` 的占位密码比对**不可用于生产**，必须在 Phase 2 完成前替换。
2. `GlobalExceptionHandler` 对 `401` 段硬编码 `X-Auth-Reason: no-session`，`superseded` 场景需单独区分。
3. 时段余量预热为启动全量，未做增量发布事件联动。
