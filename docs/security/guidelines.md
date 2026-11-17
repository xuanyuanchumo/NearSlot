---
title: 安全设计规范
status: current
source_of_truth:
  - ../../docs/security/guidelines.md
updated: 2026-09-05
---

# 安全设计规范

## 1. 认证与凭证（ADR-002 全套）

- AT=JWT 30min（claims 仅 userId+version，不塞敏感信息）；RT=UUID 7d，HttpOnly+SameSite=Lax Cookie（HTTPS 下 Secure+None），禁止 RT 进 localStorage。
- 登录/刷新/改密 Lua 原子族：LoginSetToken（四键原子写，禁先 DEL 再 SET）、RefreshExpiredToken（刷新不 bump version）、ReadCurrentToken（422 并发自愈，validVersion==oldVersion 才回当前凭证）。
- 改密/踢出 bump version（RedisIdWorker.nextVersion）；Caffeine 快拒 + Redis 终校验两级版本校验。
- 401 透传 X-Auth-Reason；前端禁止 Bearer null、禁止无限重试。

## 2. 防爆破与限流

- 密码登录：IP 维度 60s 5 次 + 账号 24h 10 次锁 30min（Redis INCR 窗口，PickNear 模式）；验证码 GET+DEL 原子消费 + 频控 60s/次。
- BCrypt 加盐，兼容旧哈希并登录成功自动升级（PickNear PasswordService 模式）。
- 接口限流：令牌桶 + 滑动窗口 Lua，SPI 场景扩展（hmdp-plus rate-limit 模式），Agent 会话级 30 次/60s。

## 3. 授权与数据归属

- 越权防护双闸口（ADR-010）：PromptHookChain 前置（注入检测+限流+异常降级不阻塞）+ GuardedToolCallback 执行闸口（高危 BLOCK/敏感 CONFIRM/常规 ALLOW 策略投票）。
- 数据归属：@RequiredDataPermission(resourceType) AOP 校验（owner 归属），Agent 工具与普通 Controller 同样生效。
- 高危工具黑名单（delete 类）+ 参数 pattern 拦截 + 确认工具 TTL 审批（300s）。

## 4. 注入与输入校验

- SQL：全部 MP 参数化；动态排序走白名单（SORTABLE_FIELDS），ORDER BY FIELD 保序。
- 正则校验集中 RegexPatterns（手机号/邮箱/密码强度），禁止散落字面量。
- XSS：富文本白名单过滤 + 输出转义；CSP 前端配置。
- 文件上传：UUID 散列目录 + 路径穿越校验（.. 检查）+ 类型/大小白名单；dev 本地存储 / prod 对象存储（@Profile 切换）。

## 5. 密钥与配置

- 全部密钥 .env（ADR-013）；.env.example 提交模板；禁止密钥进代码/日志/异常消息/镜像层。
- jasypt 可选加密配置项；日志脱敏：token maskToken（前 10+...+后 10），手机号掩码。

## 6. 传输与依赖

- 生产强制 HTTPS；内部服务可明文但网络隔离（bridge 内网）。
- 依赖漏洞：CI 定期 mvn dependency-check / OWASP 插件；新依赖需评审（版本锁定，禁 LATEST）。
