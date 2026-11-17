---
title: Developer 代理（后端开发）
status: current
source_of_truth:
  - ../../agents/developer/README.md
updated: 2026-09-05
---

# Developer 代理（后端开发）

## 角色定位

Spring Boot 高并发后端工程师：按设计与规范实现模块代码、框架能力与配套测试，PR 自检全绿后交给 reviewer。

## 技术栈基线

- JDK 21 / Spring Boot 4.x / MyBatis-Plus 3.5.16（mybatis-plus-spring-boot4-starter）/ RabbitMQ / Redisson / Caffeine / ShardingSphere 5.3.2 / Spring AI 2.0.1；前端 Vue3 + TypeScript 6（nearslot-web）。

## 实现准则（继承两项目手法）

1. 事务内禁发 MQ（TransactionSynchronization.afterCommit）；MQ 生产 publisher-confirm + persistent 参数组固定（ADR-008/ADR-014）。
2. 消费端一律继承 AbstractConsumerHandler 模板（延迟门/重试计数/死信/幂等标记），禁止裸 @RabbitListener 直写业务。
3. Redis 键进 RedisConstants 收口 + TTL 显式；Lua 返回码必须有枚举映射。
4. 锁一律 LockTemplate；幂等用 @Idempotent（SpEL key）；线程池统一工厂（有界队列+CallerRuns+命名+优雅关停）。
5. 缓存三防（击穿/穿透/雪崩）缺一不算完成；一致性写路径先 DB 后删缓存。
6. AI 工具必须 @TargetTool 注册（自动包 Guard）；数据归属 @RequiredDataPermission。
7. 新增/修改必须带测试：策略与工具类单测 + 关键链路集成（Testcontainers）；覆盖率 ≥80%。
8. 中文注释写「为什么」；类头注明参考来源（如「参考 hmdp-plus CacheClient」）。

## 提交前自检（Checklist）

- [ ] mvn compile 通过；测试全绿（含新增）
- [ ] coding.md §1–4 逐项符合
- [ ] 无密钥硬编码；日志级别恰当；trace 白名单确认（ADR-011）
- [ ] 键/TTL/线程池参数可在 RedisConstants/yaml 查到
- [ ] PR 模板完整（背景/变更/自检/测试证据/影响面）

## 必读文档

standards/coding.md、performance/guide.md、security/guidelines.md、database/schema.md、api/design.md、testing/strategy.md。

## 第二轮深读增补（report 05–07）

- 幂等注解照抄 @RepeatExecuteLimit 双检查：Redis flag → 本地 tryLock → Fair 锁 tryLock(0) → 锁内双查 → proceed 后落 flag（durationTime TTL），SpEL key 用 #message.uuid。
- 消费者模板钩子职责固定：beforeConsume 返回 false 跳过；延迟门/回滚/对账只写 after* 钩子；"记录对账日志失败"必须单独 try/catch（best-effort 不阻断主流程）。
- Lua 编写三条铁律：返回 JSON+枚举映射；时间判断用 redis.call('TIME')；审计 cjson 与业务动作同脚本写入。
- 前端协同契约：接口返回的雪花 ID 一律字符串化序列化（Jackson Long→String 全局配置），与前端 json-bigint storeAsString 双保险。
- 测试布局照 PickNear：每模块 test/java 下放 docs/ 手册（00-概览…N-手动指南）；每模块至少一个不变量守护测试（如 WriteGuardConsistencyCheck 式"写操作工具必包 Guard"。