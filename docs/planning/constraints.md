---
title: NearSlot 约束登记册（机器可检测优先）
status: current
source_of_truth:
  - ../../docs/planning/constraints.md
updated: 2026-11-16
---

# NearSlot 约束登记册

> **定位**：ADR 的"可执行检查"视图——每条约束给出检测手段与违反示例；可机器检测的进 CI（check_docs.py / ArchUnit / 契约测试），其余进评审清单。
> **原则**：AGENTS.md 只保留指针到本册，细则不双写；新增约束必须同时给出检测手段，否则不得入册。

## 1. 架构约束（G2 设计评审）

| ID | 约束 | 来源 | 检测手段 | 违反示例 | 门禁 |
|---|---|---|---|---|---|
| C-ARCH-01 | module 之间禁止互相依赖，跨模块走 common 接口或 RabbitMQ 事件 | ADR-001 / overview.md §3 | ArchUnit \`noClasses().that().resideInAPackage("..module..").should().dependOnClassesThat().resideInAPackage("..module..")\` | \`nearslot-module-order\` import \`nearslot-module-shop\` 的实现类 | G2/G3 |
| C-ARCH-02 | framework 不得 import 任何 module（app 除外） | overview.md 依赖铁律 | ArchUnit \`framework..\` 不依赖 \`..module..\` | framework.cache 引用 module.auth 的常量 | G2/G3 |
| C-ARCH-03 | nearslot-app 是唯一可执行 jar，唯一聚合所有模块 | ADR-001 | Maven 构建检查：仅 app 打 spring-boot repackage | 其他模块声明 boot-maven-plugin | G3 |
| C-ARCH-04 | 通用能力注解化（锁/幂等/限流/布隆/延迟队列），业务零侵入 | ADR-003 | 评审清单 + ArchUnit（framework 包被 @Aspect 引用） | 业务模块手写 SETNX 而非 @Idempotent | G3 |
| C-ARCH-05 | 新增关键决策必须补 ADR，编号只增不改 | decisions.md 格式约定 | check_docs.py C1（source_of_truth）+ reviewer 检查单 | 改了架构却有 PR 无 ADR 增量 | G2 |

## 2. 一致性约束（G3/G4）

| ID | 约束 | 来源 | 检测手段 | 违反示例 | 门禁 |
|---|---|---|---|---|---|
| C-CON-01 | Lua 返回码必须单一枚举（BaseCode），禁止魔法数字 | research/09 §F.1 | 契约测试：Lua 返回码集合 ⊆ BaseCode 枚举；grep 硬编码数字 | \`if r ~= 0\` 与 \`SUCCESS(200)\` 双口径并存 | G3/G4 |
| C-CON-02 | Redis 回补必须反转 beforeQty/afterQty，显式传方向 | ADR-005 | Lua 语义测试（Testcontainers-Redis） | 回补 trace 的 before/after 未交换 | G4 |
| C-CON-03 | orderId 与 traceId 分别取号，禁止假设同源 | research/09b §5 | 契约测试断言两值来自两次 nextId() | \`orderId = traceId = nextId()\` | G3 |
| C-CON-04 | 事务内禁发 MQ，用 TransactionSynchronization.afterCommit | research/07 §1 / coding.md | ArchUnit/评审：@Transactional 方法内无 rabbitTemplate.convertAndSend | 事务方法直接 send | G3 |
| C-CON-05 | 消费端继承统一模板（延迟门/重试/死信/幂等标记） | ADR-004 / research/07 §3 | 代码评审检查单 + 单测 | 消费者裸 @RabbitListener 无延迟门 | G3 |

## 3. 安全约束（G3/G4）

| ID | 约束 | 来源 | 检测手段 | 违反示例 | 门禁 |
|---|---|---|---|---|---|
| C-SEC-01 | 密钥全部 .env，禁止明文进代码/配置/镜像 | ADR-013 | 密钥扫描（gitleaks/CI）+ compose 强缺省语法检查 | compose 写死 Redis 密码 | G3/G5 |
| C-SEC-02 | 401 响应必须带 X-Auth-Reason；禁 Bearer null | api/design.md §4 | 契约测试 | 刷新失败返回 401 无 Reason | G4 |
| C-SEC-03 | 高危工具（预约/支付/删除）强制 CONFIRM + TTL 300s | ADR-010 / research/09 §B | Guard 策略单测 | bookAppointment 配置为 ALLOW | G4 |
| C-SEC-04 | AI 事实只来自 L0 工具证据，无真值不作答 | research/09 §G.1 | 反编造测试（无工具真值询问价格） | AI 编造时段价格 | G4 |
| C-SEC-05 | 数据归属 @RequiredDataPermission 对 Agent 工具同样生效 | security/guidelines.md §3 | 越权测试 | Agent 查他人订单成功 | G4 |

## 4. 文档与版本约束（G0/G3）

| ID | 约束 | 来源 | 检测手段 | 违反示例 | 门禁 |
|---|---|---|---|---|---|
| C-DOC-01 | frontmatter 必填 + 链接可达 + current 索引可达 + source_of_truth 存在 | standards/doc.md §3 | check_docs.py（C1–C6） | 文档缺 updated 或坏链接 | 全门禁 |
| C-DOC-02 | 一主题一 current；legacy 必填 superseded_by 且不被索引链接 | doc.md §1 | check_docs.py C1/C3 | legacy 仍出现在 README 导航 | G0 |
| C-DOC-03 | 中文注释齐全；Javadoc/Lua 头注释不漂移 | standards/coding.md / research/12 §4 | reviewer 检查单 | Lua 头注释 KEYS 与 Java 传参不符（research/09 §F.5） | G3 |
| C-ARCH-06 | **成熟库优先**：锁/限流/延迟队列/ID/密码/缓存/布隆必须用既定库，禁自研替代 | ADR-016 + standards/library-first.md | `scripts/dev/check-wheels.sh`：grep 拦截手写轮子 | 手写 AtomicLong 生成订单号 | G2/G3 |
| C-DOC-05 | 接口契约以 api/openapi.json 为唯一事实源，由 /v3/api-docs 生成入库 | ADR-018 | CI contract job：生成 + diff；删路径/删方法即阻断 | 手写接口文档与代码不一致 | G2/G5 |
| C-DOC-04 | 版本四端一致：pom revision / package.json / API 版本 / migration 版本 | research/10 §版本 | 版本同步脚本（Phase 0 待实现骨架） | 前端 0.0.0 后端 0.0.1-SNAPSHOT 无握手 | G5 |

## 5. 落地与检测状态

| 约束类 | 可机器检测 | 当前载体 | 状态 |
|---|---|---|---|
| 架构 | C-ARCH-01/02/03 | ArchUnit 测试类（Phase 1 落地） | 规则已定义，待代码 |
| 一致性 | C-CON-01/02/03 | Lua 契约测试（Phase 2/4） | 规则已定义，待代码 |
| 安全 | C-SEC-01 | 密钥扫描（本节 CI 骨架） | 规则已定义，待 CI |
| 文档 | C-DOC-01/02 | check_docs.py | ✅ 已实现并验证（90 文档绿） |

> 未在 CI 落地的检测手段在对应阶段（Phase 1/2/4）实现；本册随 ADR 增量同步更新。
