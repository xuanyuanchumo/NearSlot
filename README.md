---
title: NearSlot
status: current
source_of_truth:
  - docs/README.md
updated: 2026-11-16
---

# NearSlot · 通用本地生活服务预约平台

> **一句话**：面向**本地生活服务**的**时段预约平台**——平台提供通用预约内核，**具体业态由店铺配置决定**（到店/上门、人力/场地/设备皆可），把 PickNear 的认证/Agent/多级缓存与 hmdp-plus 的一致性闭环/框架五件套，落到"时段槽位"这个与券秒杀根本不同的领域上。
>
> **当前阶段**：核心功能已实现并通过端到端验证；CI 全绿（含覆盖率 G4 门禁 80%）。剩余项见 [backlog](docs/planning/backlog.md)。

## 项目定位

- **业务边界**：本地生活服务 = 在特定地理范围内、依托线下实体资源（门店/人员/场地/设备）交付、**需确定时间**才能消费的服务。区别于实物电商（可快递）与券秒杀（不锁时段）。
- **核心差异化**：库存不是"数量"，而是**带时间坐标的稀缺资源**（人的时间 / 场地时段 / 设备档期）。
- **业务主链路**：按地理位置找服务商 → 选服务项 → 选时段 → 抢约（Hold 两段式）→ 支付 → 候补提升 → 履约提醒 → **核销**。
- **多商户平台**：平台代码**不出现具体业态词**（美甲/推拿/维修等），只使用通用概念——**服务项 / 服务者 / 槽位 / 核销**；业态由店铺配置决定。详见 [平台架构研究](docs/architecture/platform-architecture-research.md)、[业务域澄清](docs/architecture/business-domain.md)。
- **技术栈**：JDK 21 + Spring Boot 4 + Maven 多模块（模块化单体）+ MyBatis-Plus + Redis 7/Redisson/Caffeine + RabbitMQ + Spring AI 2.0.1/DashScope/Langfuse + Vue3/TypeScript 6(strict)（独立模块 nearslot-web）。
- **技术差异化**：时段槽位并发内核（Lua 原子预扣 + 防重复预约 + 服务者时间轴不重叠）、Hold 两段式预约、异步候补提升、对账闭环、时空约束（GEO/行程缓冲/DST）。详见 [特色域研究](docs/research/08-nearslot-domain-research.md)。

## 当前实现规模（实测）

| 指标 | 数值 |
|---|---|
| Maven 模块 | 9（common / framework / auth / shop / appointment / order / content / agent / app） |
| Java 文件 | 236 |
| 数据库表 | 26（含迁移脚本幂等重放） |
| HTTP 接口 | 74 |
| 单元测试 | BUILD SUCCESS，行覆盖率 **80.9%**（G4 门槛 80%） |
| CI | build / contract 双 job 全绿；集成测试 10 用例（真实 MySQL/Redis/RabbitMQ） |

## 文档体系（全部 current）

| 类别 | 文档 | 状态 | 说明 |
|---|---|---|---|
| 文档中心 | [docs/README.md](docs/README.md) | ✅ current | 全目录导航 + 治理规则 + 任务路由表 |
| 研究 | [01 PickNear](docs/research/01-picknear-research.md) · [02 hmdp-plus](docs/research/02-hmdp-plus-research.md) | ✅ current | 两项目源码级深度研究（11/9 节） |
| 研究 | [03 架构与前端](docs/research/03-framework-frontend.md) · [04 Agent 与质量评审](docs/research/04-agent-quality-review.md) | ✅ current | 对比取舍 + 质量体系 |
| 研究 | [05 前端与 UX](docs/research/05-frontend-ux-research.md) · [06 工程生命周期](docs/research/06-engineering-lifecycle-research.md) · [07 高并发内核](docs/research/07-concurrency-core-research.md) | ✅ current | 第二轮深读 |
| 研究 | **[08 NearSlot 特色域](docs/research/08-nearslot-domain-research.md)** | ✅ current/新增 | 时段预约内核（本次增补） |
| 研究 | [09 PickNear 核验](docs/research/09-picknear-verification.md) · [09b hmdp-plus 核验](docs/research/09b-hmdp-plus-verification.md) | ✅ current | 源码逐行核验：CONFIRMED/CORRECTED/NEW，修正 6+4 处事实错误 |
| 深研 | [10 后端工程](docs/research/10-backend-engineering-deep.md) · [11 前端 UX](docs/research/11-frontend-ux-deep.md) · [12 质量治理](docs/research/12-quality-governance-deep.md) · [00 覆盖矩阵](docs/research/00-coverage-matrix.md) | ✅ current | 覆盖全部 21 研究维度 |
| 架构 | [overview](docs/architecture/overview.md) · [decisions(ADR-001~034)](docs/architecture/decisions.md) | ✅ current | 模块化单体 + 34 条 ADR |
| 平台定位 | [platform-architecture](docs/architecture/platform-architecture-research.md) · [business-domain](docs/architecture/business-domain.md) · [business-scope](docs/architecture/business-scope-research.md) | ✅ current | 通用多商户平台 + 业态由店铺配置 |
| 需求/质量 | [功能需求](docs/quality/functional-requirements.md) · [质量属性/SLO](docs/quality/quality-attributes.md) · [评审体系](docs/quality/review-system.md) | ✅ current | FR-1~12 + 门禁 G0~G6 |
| 规范 | [coding](docs/standards/coding.md) · [git](docs/standards/git.md) · [doc](docs/standards/doc.md) · [frontend](docs/standards/frontend.md) | ✅ current | 4 份工程规范 |
| 设计 | [api](docs/api/design.md) · [database/schema](docs/database/schema.md) | ✅ current | 接口约定 + 表/分片/Redis 键 |
| 运维 | [deployment](docs/deployment/guide.md) · [performance](docs/performance/guide.md) · [security](docs/security/guidelines.md) · [testing](docs/testing/strategy.md) | ✅ current | 部署/性能/安全/测试 |
| 协作代理 | [agents/overview](docs/agents/overview.md) + designer/developer/reviewer/tester 配置 | ✅ current | 四代理门禁协作 |

**未完成事项**：统一登记在 [docs/planning/backlog.md](docs/planning/backlog.md)（唯一事实源，不在 README 重复维护）。
当前 P0：**BL-PAY-01 支付回调网关签名校验**（上线前必须完成）。

- **项目生命周期文档目录** → [lifecycle/README](docs/lifecycle/README.md)（计划/需求/设计/开发/测试/验收/质量/安全运维 8 阶段，49 份模板+索引）
- **本地开发** → [local-dev](docs/deployment/local-dev.md)（容器跑中间件 + 本地跑前后端，含清理脚本）
- **开发里程表** → [roadmap](docs/planning/roadmap.md)（Phase 0–5 / 门禁 G0–G6）· [任务规划 WBS](docs/planning/tasks.md)（39 任务）· [具体开发计划](docs/planning/development-plan.md)（DCIVF 闭环 + Phase 0–5 详细方案）

## 快速导航

- 想了解"为什么这样设计" → [docs/README.md 快速路由表](docs/README.md)
- 想动手前看架构 → [总体架构](docs/architecture/overview.md) + [ADR](docs/architecture/decisions.md)
- 想做抢约/候补开发 → [特色域研究](docs/research/08-nearslot-domain-research.md) + [数据库设计](docs/database/schema.md)
