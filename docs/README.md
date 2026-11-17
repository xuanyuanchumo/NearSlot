---
title: NearSlot 文档中心
status: current
source_of_truth:
  - README.md
updated: 2026-09-05
---

# NearSlot 文档中心

> 本目录是 NearSlot 的开发文档、规范文档与质量评审体系唯一入口。治理规则继承 PickNear（frontmatter + 检查脚本），文档风格继承 hmdp-plus（每个特性给"场景 + 做法"）。

## 目录结构与导航

| 目录 | 内容 | 主题文档 |
|---|---|---|
| research/ | 两项目深度研究报告（学习成果）+ NearSlot 特色域研究 | [01 PickNear](research/01-picknear-research.md) · [02 hmdp-plus](research/02-hmdp-plus-research.md) · [03 架构与前端](research/03-framework-frontend.md) · [04 Agent 与质量评审](research/04-agent-quality-review.md) · [05 前端与 UX 深读](research/05-frontend-ux-research.md) · [06 工程生命周期深读](research/06-engineering-lifecycle-research.md) · [07 高并发内核深读](research/07-concurrency-core-research.md) · **[08 NearSlot 特色域研究](research/08-nearslot-domain-research.md)** · [09 PickNear 核验](research/09-picknear-verification.md) · [09b hmdp-plus 核验](research/09b-hmdp-plus-verification.md) · [10 后端工程深研](research/10-backend-engineering-deep.md) · [11 前端 UX 深研](research/11-frontend-ux-deep.md) · [12 质量治理深研](research/12-quality-governance-deep.md) · [00 覆盖矩阵](research/00-coverage-matrix.md) · **[14 数据库选型研究](research/14-database-selection-research.md)** |
| architecture/ | 总体架构与关键决策 | [overview](architecture/overview.md) · [decisions(ADR)](architecture/decisions.md) |
| api/ | API 设计规范与核心接口 | [design](api/design.md) |
| database/ | 数据模型与分片策略 | [schema](database/schema.md) |
| standards/ | 工程化规范（含 [成熟库优先](standards/library-first.md)） | [coding](standards/coding.md) · [git](standards/git.md) · [doc](standards/doc.md) · [frontend(含 UX/UI/响应式)](standards/frontend.md) |
| quality/ | 质量属性/评审体系/功能需求 | [quality-attributes](quality/quality-attributes.md) · [review-system](quality/review-system.md) · [functional-requirements](quality/functional-requirements.md) |
| testing/ | 测试体系 | [strategy](testing/strategy.md) |
| deployment/ | 部署运维 | [guide](deployment/guide.md) · [local-dev(本地开发与容器编排)](deployment/local-dev.md) · [docker-mirror(镜像加速)](deployment/docker-mirror.md) |
| performance/ | 性能设计与压测方法 | [guide](performance/guide.md) |
| security/ | 安全规范 | [guidelines](security/guidelines.md) |
| agents/ | 四代理协作说明 | [overview](agents/overview.md) |
| lifecycle/ | **项目生命周期文档目录**（模板+索引，49 份） | [总索引](lifecycle/README.md)（含截图资料→生命周期映射表） |
| planning/ | 开发里程表与任务规划（含 [现状审计与三版方案](planning/audit-2026-11-17.md)） | [roadmap](planning/roadmap.md) · [tasks](planning/tasks.md) · [development-plan](planning/development-plan.md)（含 Phase 0–5 详细执行方案） |

## 开工必读

- [指导核心（文档为纲）](指导核心.md) —— **最高依据**：docs 为主、两项目为辅、及时回写
- [研发思维原则与执行规程](提示词.md) —— 每次开发任务开始前必读；含开工前置清单与硬性红线。

## 文档治理规则（继承 PickNear）

1. frontmatter 必填：status(draft|current|legacy)、source_of_truth（指向本仓库真实来源路径）、updated；被替代文档必须填 superseded_by。
2. 默认不新建文档：优先扩展现有文档；确需新建，先在本文导航表登记。
3. 历史靠 Git：不建 archive/ 目录；废弃文档标 legacy + superseded_by。
4. 确定性检查：scripts/docs/check_docs.py 校验 frontmatter 必填、站内链接可达、current 文档从本 README 可达、source_of_truth 存在（Phase 1 落地该脚本）。
5. 中文优先：正文一律中文；术语首现给英文；代码注释规范见 standards/coding.md。

## 快速路由（按任务找文档）

| 你要做的事 | 先读 |
|---|---|
| 了解两项目为什么这样设计 | research/ 七篇（05–07 为第二轮深读） |
| 写前端秒杀/预约交互 | research/05 第 3 节 + standards/frontend.md |
| 实现幂等/对账/回滚 | research/07 §3–4 + performance/guide.md |
| 登记技术债 | research/06 §5 格式 → quality/ 评审对照 |
| 搭模块骨架 | architecture/overview.md + decisions.md |
| 写接口 | api/design.md + standards/coding.md |
| 建表/写分片 | database/schema.md |
| 抢约/候补/对账开发 | database/schema.md 第三节 + performance/guide.md 第二节 |
| 写前端页面 | standards/frontend.md |
| 提测/评审 | quality/review-system.md（五维检查单） |
| 压测 | performance/guide.md 第四节（必须记录条件） |
| 部署 | deployment/guide.md（密钥走 .env，禁明文） |
