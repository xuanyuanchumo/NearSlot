---
title: 编码规范
status: current
source_of_truth:
  - ../../docs/standards/coding.md
updated: 2026-09-05
---

# 编码规范

## 1. 通用

- JDK 21：优先 record（DTO/值对象）、sealed 接口（策略族）、switch 表达式；虚拟线程仅用于 IO 密集批处理，业务线程池仍用平台线程（可控性）。
- 注释：公共 API 与复杂逻辑必须有中文 Javadoc（说明「为什么」而不只是「是什么」）；类头注明来源项目参考（如「参考 PickNear TokenService」）。
- 命名：类 UpperCamel；方法/变量 lowerCamel；常量 UPPER_SNAKE；包名全小写单层语义（auth/shop/appointment/order/agent）。
- 方法 ≤60 行、类 ≤400 行、圈复杂度 ≤10（超出必须拆策略或提取类）。

## 2. 异常与结果

- 业务异常统一 BizException(code)；系统异常 HmdpFrameException 风格封装；禁止吞异常（catch 后必须 log.warn 带 context 或重抛）。
- Lua 返回 code 必须有枚举映射（SeckillOrderCode 模式），禁止魔法数字透传（PickNear 已知设计债，NearSlot 修正为枚举对齐）。
- Controller 只做参数校验与编排，业务在 Service；事务边界在 Service 方法（@Transactional），事务内禁止发 MQ（用 TransactionSynchronization.afterCommit，PickNear H-5 教训）。

## 3. 并发与资源

- 线程池：统一工厂创建（有界队列+CallerRuns+命名+UncaughtExceptionHandler+@PreDestroy），禁止 Executors 快捷方法。
- 锁：一律 LockTemplate（SET NX+UUID 所有者+Lua 校验释放），禁止裸 setIfAbsent 不带释放保护。
- Redis 操作：键常量进 RedisConstants 单文件收口；TTL 显式；pipeline 用于批量。

## 4. MyBatis-Plus

- 查询用 LambdaQueryWrapper；更新带乐观锁条件（stock=stock-1 where stock>0）。
- 分页必须 Page 参数；type-aliases 分模块注册；逻辑删除统一字段 deleted。

## 5. 提交前自检（Developer 代理 checklist）

- [ ] 编译通过 mvn compile；新增逻辑有测试；日志级别恰当（不刷屏）；无密钥硬编码；中文注释齐；涉及 Redis/MQ/缓存的键与 TTL 在 RedisConstants/yaml 中可查。
