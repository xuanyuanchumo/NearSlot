---
title: NearSlot
status: current
source_of_truth:
  - docs/README.md
updated: 2026-11-16
---

# NearSlot（预约槽位系统）

> **一句话**：一个面向"技师上门服务"场景的**时段预约系统**——把 PickNear 的认证/Agent/多级缓存与 hmdp-plus 的一致性闭环/框架五件套，落到"时段槽位"这个与券秒杀根本不同的领域上。
> **当前阶段**：开发前研究准备期。架构、需求、规范、质量体系文档已就绪；源码尚未开始实现。

## 项目定位

- **业务**：用户按地理位置找服务商/技师 → 选时段 → 抢约 → 支付 → 履约提醒 → 核销。
- **技术**：JDK 21 + Spring Boot 4 + Maven 多模块（模块化单体）+ Redis 7/Redisson/Caffeine + RabbitMQ + ShardingSphere(2 库×2 表) + Spring AI 2.0.1/DashScope/Langfuse + Vue3/TypeScript 6(strict)（独立模块 nearslot-web）。
- **差异化**：时段槽位并发内核（防重复预约 + 技师时间轴不重叠）、Hold 两段式预约、异步候补提升、时空约束（GEO/行程缓冲/DST）。详见 [特色域研究](docs/research/08-nearslot-domain-research.md)。

## 开发前准备工作完成状态

| 类别 | 文档 | 状态 | 说明 |
|---|---|---|---|
| 文档中心 | [docs/README.md](docs/README.md) | ✅ current | 全目录导航 + 治理规则 + 任务路由表 |
| 研究 | [01 PickNear](docs/research/01-picknear-research.md) · [02 hmdp-plus](docs/research/02-hmdp-plus-research.md) | ✅ current | 两项目源码级深度研究（11/9 节） |
| 研究 | [03 架构与前端](docs/research/03-framework-frontend.md) · [04 Agent 与质量评审](docs/research/04-agent-quality-review.md) | ✅ current | 对比取舍 + 质量体系 |
| 研究 | [05 前端与 UX](docs/research/05-frontend-ux-research.md) · [06 工程生命周期](docs/research/06-engineering-lifecycle-research.md) · [07 高并发内核](docs/research/07-concurrency-core-research.md) | ✅ current | 第二轮深读 |
| 研究 | **[08 NearSlot 特色域](docs/research/08-nearslot-domain-research.md)** | ✅ current/新增 | 时段预约内核（本次增补） |
| 研究 | [09 PickNear 核验](docs/research/09-picknear-verification.md) · [09b hmdp-plus 核验](docs/research/09b-hmdp-plus-verification.md) | ✅ current | 源码逐行核验：CONFIRMED/CORRECTED/NEW，修正 6+4 处事实错误 |
| 深研 | [10 后端工程](docs/research/10-backend-engineering-deep.md) · [11 前端 UX](docs/research/11-frontend-ux-deep.md) · [12 质量治理](docs/research/12-quality-governance-deep.md) · [00 覆盖矩阵](docs/research/00-coverage-matrix.md) | ✅ current | 覆盖全部 21 研究维度 |
| 架构 | [overview](docs/architecture/overview.md) · [decisions(ADR-001~013)](docs/architecture/decisions.md) | ✅ current | 模块化单体 + 13 条 ADR |
| 需求/质量 | [功能需求](docs/quality/functional-requirements.md) · [质量属性/SLO](docs/quality/quality-attributes.md) · [评审体系](docs/quality/review-system.md) | ✅ current | FR-1~5 + 门禁 G0~G6 |
| 规范 | [coding](docs/standards/coding.md) · [git](docs/standards/git.md) · [doc](docs/standards/doc.md) · [frontend](docs/standards/frontend.md) | ✅ current | 4 份工程规范 |
| 设计 | [api](docs/api/design.md) · [database/schema](docs/database/schema.md) | ✅ current | 接口约定 + 表/分片/Redis 键 |
| 运维 | [deployment](docs/deployment/guide.md) · [performance](docs/performance/guide.md) · [security](docs/security/guidelines.md) · [testing](docs/testing/strategy.md) | ✅ current | 部署/性能/安全/测试 |
| 协作代理 | [agents/overview](docs/agents/overview.md) + designer/developer/reviewer/tester 配置 | ✅ current | 四代理门禁协作 |

**已知待办（Phase 1）**：
1. `scripts/docs/check_docs.py` 确定性门禁脚本——规范已写，脚本待实现。
2. 根 README 此前缺失——本次补齐。
3. 特色域研究中的时空约束阈值（行程缓冲/过期窗口/违约次数）需真实数据与压测确定。
4. 门禁统一：已补 G0 治理就绪门（见 quality/review-system.md），待 ADR-014 正式记录。
5. 拦截器阈值口径已统一：命中≤1ms / 未命中<5ms / >100ms 告警，详见 research/12 §1。
6. 源码级事实纠错见 research/09 §F 与 09b §5（orderId≠traceId、Lua 返回码契约、allowedCities 未生效等）。

- **项目生命周期文档目录** → [lifecycle/README](docs/lifecycle/README.md)（计划/需求/设计/开发/测试/验收/质量/安全运维 8 阶段，49 份模板+索引）
- **本地开发** → [local-dev](docs/deployment/local-dev.md)（容器跑中间件 + 本地跑前后端，含清理脚本）
- **开发里程表** → [roadmap](docs/planning/roadmap.md)（Phase 0–5 / 门禁 G0–G6）· [任务规划 WBS](docs/planning/tasks.md)（39 任务）· [具体开发计划](docs/planning/development-plan.md)（DCIVF 闭环 + Phase 0–5 详细方案）

## 快速导航

- 想了解"为什么这样设计" → [docs/README.md 快速路由表](docs/README.md)
- 想动手前看架构 → [总体架构](docs/architecture/overview.md) + [ADR](docs/architecture/decisions.md)
- 想做抢约/候补开发 → [特色域研究](docs/research/08-nearslot-domain-research.md) + [数据库设计](docs/database/schema.md)
