---
title: NearSlot 设计阶段文档目录（索引与路由）
status: current
source_of_truth:
  - ../../../docs/lifecycle/03-设计阶段/README.md
updated: 2026-11-16
---

# NearSlot 设计阶段文档目录（索引与路由）

> **结论先行**：本目录只承载 03-设计阶段的**索引 + 模板骨架**。架构/API/表结构/安全/性能/UX 的事实以 docs/ 既有文档为**唯一事实源（SSOT）**；模板仅写结构、NearSlot 填写提示与检查单，**禁止复制既有设计内容**，一律用相对链接引用。
> **适用门禁**：G2 设计评审。模板状态为「模板/待填」时不得作为已交付证据。责任代理：designer，reviewer 复核；治理遵循 [standards/doc.md](../../standards/doc.md) 与 [生命周期总索引](../README.md)。

## 1. 目录清单

| 编号 | 文档 | 定位 | 主 SSOT（相对链接） | 状态 |
|---|---|---|---|---|
| DSGN-01 | [体系结构设计说明书](体系结构设计说明书.md) | 上下文/技术栈/模块/依赖铁律/关键链路/部署视图（含时段预约差异） | [overview](../../architecture/overview.md) + [research/08](../../research/08-nearslot-domain-research.md) | 模板 |
| DSGN-02 | [概要设计说明书](概要设计说明书.md) | 模块级设计、接口边界、跨模块 common 接口/RabbitMQ 事件 | [overview §3–4](../../architecture/overview.md) + [ADR-001/003/004/008](../../architecture/decisions.md) | 模板 |
| DSGN-03 | [软件设计说明书](软件设计说明书.md) | 总体软件设计（架构+API+安全+性能+技术解决方案） | [overview](../../architecture/overview.md) + [api](../../api/design.md) + [security](../../security/guidelines.md) + [performance](../../performance/guide.md) | 模板 |
| DSGN-04 | [详细设计说明书](详细设计说明书.md) | 认证 Lua / 抢约 Lua / 对账 三条核心链方法级设计 | [research/08](../../research/08-nearslot-domain-research.md) + [overview §4](../../architecture/overview.md) + [api §3](../../api/design.md) | 模板 |
| DSGN-05 | [数据库设计说明书](数据库设计说明书.md) | 表清单/分片/Redis 键/DDL/索引与唯一约束 | [schema](../../database/schema.md) + [research/08 §2](../../research/08-nearslot-domain-research.md) | 模板 |
| DSGN-06 | [接口设计说明书](接口设计说明书.md) | 端点索引/错误码/SSE；明细以 springdoc openapi 为 SSOT | [api](../../api/design.md) | 模板 |
| DSGN-07 | [界面与交互设计说明书](界面与交互设计说明书.md) | 断点/旅程/组件契约/a11y/SSE 状态机 | [frontend](../../standards/frontend.md) + [research/11](../../research/11-frontend-ux-deep.md) | 模板 |
| DSGN-08 | [购买复用自制分析表](购买复用自制分析表.md) | 各模块 PickNear/hmdp-plus 复用 vs 新建决策 | [research/01](../../research/01-picknear-research.md) + [research/02](../../research/02-hmdp-plus-research.md) + [research/08](../../research/08-nearslot-domain-research.md) | 模板 |

## 2. 什么任务读什么文档（路由表）

| 你要做的事 | 先读（SSOT） | 再用（模板） |
|---|---|---|
| 确认系统边界/技术栈/模块依赖 | [architecture/overview.md](../../architecture/overview.md) | DSGN-01 |
| 查某条 ADR 的取舍 | [architecture/decisions.md](../../architecture/decisions.md) | DSGN-01/05/08 |
| 设计时段预约差异化内核（槽位/Hold/候补/三闸门） | [research/08](../../research/08-nearslot-domain-research.md) | DSGN-01/04/05 |
| 划分模块边界与跨模块事件 | [architecture/overview.md §3–4](../../architecture/overview.md) | DSGN-02 |
| 写总体软件/技术方案 | [overview](../../architecture/overview.md)+[api](../../api/design.md)+[security](../../security/guidelines.md)+[performance](../../performance/guide.md) | DSGN-03 |
| 写核心链方法级设计 | [overview §4](../../architecture/overview.md)+[api §3](../../api/design.md) | DSGN-04 |
| 建表/分片/Redis 键/唯一约束 | [database/schema.md](../../database/schema.md) | DSGN-05 |
| 定接口契约/错误码/SSE | [api/design.md](../../api/design.md)+springdoc openapi.json | DSGN-06 |
| 写前端页面/旅程/a11y | [standards/frontend.md](../../standards/frontend.md)+[research/11](../../research/11-frontend-ux-deep.md) | DSGN-07 |
| 决定复用还是自研 | [research/01](../../research/01-picknear-research.md)+[research/02](../../research/02-hmdp-plus-research.md) | DSGN-08 |

## 3. SSOT 引用清单（禁止复制内容，只链接）

| 权威文档 | 覆盖主题 | 引用方模板 |
|---|---|---|
| [architecture/overview.md](../../architecture/overview.md) | 系统上下文、9 Maven 模块、依赖铁律、关键链路、部署视图 | DSGN-01/02/03/04 |
| [architecture/decisions.md](../../architecture/decisions.md) | ADR-001…013 | DSGN-01/02/05/08 |
| [api/design.md](../../api/design.md) | REST、统一响应、501xx 错误码、鉴权头、幂等、SSE | DSGN-03/04/06 |
| [database/schema.md](../../database/schema.md) | 2 库×2 表分片、表清单、Redis 键、DDL | DSGN-05 |
| [security/guidelines.md](../../security/guidelines.md) | 认证/授权/注入/密钥/传输 | DSGN-03 |
| [performance/guide.md](../../performance/guide.md) | 多级缓存、三防、热点链、压测 | DSGN-03 |
| [research/08](../../research/08-nearslot-domain-research.md) | 槽位行、Hold、候补、技师时间轴、领域三闸门、uq 约束 | DSGN-01/04/05/08 |
| [research/11](../../research/11-frontend-ux-deep.md) | 设计令牌、响应式、旅程、a11y、SSE 状态机 | DSGN-07 |
| [standards/frontend.md](../../standards/frontend.md) | 技术栈、断点、UX、可访问性 | DSGN-07 |

## 4. 模板通用规范（所有模板必含）

1. **文档信息表**：编号 / 版本 / 状态(模板|待填|生效) / 责任代理(designer) / 关联 FR / 关联 ADR / 关联既有文档（相对链接）。
2. **正文结构**：表格优先、结论前置；每节给 NearSlot 填写提示（时段预约 / Hold / 候补 / 对账 / AI 双闸口）。
3. **检查单**：对齐门禁五维（功能 / 安全 / 性能 / 可维护 / 可观测）。
4. **变更记录表**：版本 / 日期 / 变更人 / 说明 / 关联 ADR 或 standards 反哺。

## 5. 维护

- 新增或改动模板先在 [生命周期总索引](../README.md) §3.3 登记；SSOT 更新后同步模板的链接与检查单，不在模板内重写事实。
- 每份模板填制完成由 reviewer 核对：无孤儿文档（均有 FR/ADR 关联）、无与 SSOT 重复或冲突的表述。

