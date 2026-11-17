---
title: G2 设计评审门禁记录
status: current
source_of_truth:
  - G2-design.md
updated: 2026-11-16
---

# G2 设计评审门禁记录

| 项 | 内容 |
|---|---|
| 阶段 | Phase 1（W1-2） |
| 结论 | **通过（自动检查全绿，人签待办）** |

## 证据
| 检查 | 结果 |
|---|---|
| \`mvn -B clean test\`（9 模块 reactor） | ✅ BUILD SUCCESS |
| 模块数 | 9（common/framework/6 业务模块/app），revision+flatten 统一 0.0.1-SNAPSHOT |
| 依赖铁律 | app 唯一聚合；framework 只依赖 common + 中间件 starter；ArchUnit 契约已就位（assumeTrue 防误绿） |
| 单测 | 3 个框架单测（缓存穿透空值、ArchUnit 依赖铁律、幂等/锁 API）+ 前端 5 个 vitest，全绿 |
| 返回码单一枚举 | BaseCode 统一 501xx/100xx（C-CON-01），Lua 与 Java 共享码表 |
| 文档四检 | ✅ 95 文档全绿 |
| compose | 5 服务（mysql/redis/rabbitmq/app/web）全部 healthcheck + .env 强缺省语法，YAML 解析通过 |
| 框架能力（ADR-016 成熟库） | Redisson 锁/限流/延迟队列 + Spring AMQP 消费模板 + Caffeine 三防缓存 + 布隆 + 幂等切面，全部落地 |
| 前端工具链（ADR-017） | Vite+ 1.6：vp check 35 文件格式/lint 零告警、vp build 产出 dist、vp test 5/5 通过 |
| 前后端分离（ADR-015） | nearslot-web 独立模块；Nginx 托管静态资源 + 反代 /api + SSE 免缓冲 |

## 版本决策（需人知悉）
- 目标并落地 Spring Boot 4.0.0（**保持 Boot 4，不降级**）。早期「降级 Boot 3.5.6」的权衡已撤销：MyBatis-Plus 3.5.16 提供 mybatis-plus-spring-boot4-starter，Boot 4 的 starter 坐标变更已在 Phase 1 适配。源项目证据（PickNear 3.4.4 / hmdp-plus 3.5.4）仅作历史事实引用，不构成版本锁定。
- 该决策不违反架构（模块化单体/依赖铁律不变），仅运行时基线版本。

## 待办（非 Blocker）
- [ ] G2 人签（HITL）。
- [ ] Phase 2 业务模块代码落地后，ArchUnit assumeTrue 自动生效，验证依赖铁律真实无违规。
- [ ] Testcontainers 集成测试（真实 Redis/MySQL/RabbitMQ）在 Phase 4 接入。

## Blocker
无。
