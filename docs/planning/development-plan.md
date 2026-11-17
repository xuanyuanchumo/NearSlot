---
title: NearSlot 具体开发计划（落地与闭环方法论）
status: current
source_of_truth:
  - ../../docs/planning/development-plan.md
updated: 2026-11-16
---

# NearSlot 具体开发计划：落地与闭环方法论

> **定位**：把 [roadmap.md](roadmap.md) 的阶段与 [tasks.md](tasks.md) 的任务，转化为"可开发、可验证、可回环"的执行方案。
> **核心承诺**：每一份设计都有对应的**落地物**，每一次落地都有对应的**闭环验证**，每一次验证失败都能**反哺设计**。
> **适用**：Phase 0–5 全部开发活动；四代理（designer/developer/reviewer/tester）共用一套语言。

## 1. 落地与闭环的定义（先统一口径）

| 概念 | 定义 | 反例（不算落地/闭环） |
|---|---|---|
| **落地** | 设计文档中的每条约定 → 至少一个可检查的工件（代码/配置/SQL/Lua/测试/脚本） | 只在文档写"采用 Lua 预扣"却无脚本与测试 |
| **闭环** | 工件 → 自动化验证 → 门禁结论 → 失败回环修复 → 通用问题反哺规范 | 测试红了但继续开发；评审意见不落文件 |
| **可追溯** | 需求 → 设计 → 代码 → 测试 → 门禁，双向可查（编号贯通） | 需求与测试无对应关系 |

## 2. DCIVF 五环闭环模型（每个任务强制走完）

```
D 设计落位 → C 契约先行 → I 实现落地 → V 验证闭环 → F 反哺闭环
     ↑                                                  │
     └────────────── 通用问题回流下一迭代 ────────────────┘
```

| 环 | 动作 | 产出工件 | 完成判据 |
|---|---|---|---|
| **D 设计落位** | 把 ADR/设计文档拆成具体接口、表、Redis 键、返回码、状态机 | 契约草案（附 ADR 编号） | 每个约定有唯一编号（FR-x / ADR-x / API-x） |
| **C 契约先行** | 先冻结契约再写代码：OpenAPI 注解、Lua 返回码枚举、DDL、RedisConstants | `openapi.json` / `BaseCode` 枚举 / `sql/N_*.sql` / 常量类 | 契约测试先写且为红（TDD） |
| **I 实现落地** | 骨架→能力→集成顺序编码；测试与代码同 PR | 代码 + 单测 + 中文注释 | 编译绿；覆盖率达标；无占位符 |
| **V 验证闭环** | 单测→集成→契约→门禁，逐级自动化 | JaCoCo 报告 / Testcontainers 结果 / 门禁记录 `NS-G{n}-NNN` | 全绿才过；红则回到 I 环 |
| **F 反哺闭环** | 两次同类 blocker → 更新 standards/ 或新增 ADR → check_docs 校验 | ADR 新增/规范修订/文档同步 | 规范与代码一致；可检索到变更记录 |

> **纪律**：V 环失败不允许跳过；确需带病推进须人签「有条件通过」并登记 `NS-G{n}-NNN`，下次门禁前清零（roadmap.md §5）。

## 3. 追溯矩阵（双向可查）

| 层级 | 编号体系 | 来源文档 | 被谁验证 |
|---|---|---|---|
| 需求 | FR-1.1 ~ FR-5.3 | [functional-requirements.md](../quality/functional-requirements.md) | GWT 矩阵 + E2E |
| 质量 | SLO-1 ~ SLO-11 | [quality-attributes.md](../quality/quality-attributes.md) | 压测报告（带条件） |
| 设计 | ADR-001 ~ ADR-014 | [decisions.md](../architecture/decisions.md) | G2 设计评审 + ArchUnit |
| 接口 | API-v1-xxx | [api/design.md](../api/design.md) + springdoc 生成 openapi | 契约测试 |
| 数据 | TBL-xxx / KEY-xxx | [schema.md](../database/schema.md) | DDL 幂等测试 + Lua 语义测试 |
| 任务 | NS-P{phase}-T{NN} | [tasks.md](tasks.md) | 门禁出口证据 |

## 4. Definition of Done（三层）

| 层级 | DoD |
|---|---|
| **单任务** | 契约先行；代码+测试同 PR；中文注释齐全；commit 合规；自检清单全绿 |
| **单阶段** | 出口门禁通过；Blocker=0；check_docs.py + ArchUnit + 契约测试全绿；文档与代码同步；阶段复盘完成 |
| **项目** | 11 项 SLO 达标且数字带条件；对账 ABNORMAL=0；0 越权；回滚演练成功；人签 G6 |

## 5. 阶段落地重心与闭环证据总览

| 阶段 | 落地重心 | 核心闭环证据 |
|---|---|---|
| Phase 0 | 治理脚本与门禁载体 | 负向用例全被拦截 + G0 人签 |
| Phase 1 | 九模块骨架 + 框架八能力 | mvn install 绿 + ArchUnit 无违规 + 框架单测 |
| Phase 2 | 认证/时段/槽位/Hold/候补/对账主链 | Lua 语义全绿 + 并发 100 抢 1 断言 + 对账 0 差异 |
| Phase 3 | AI 规划/双闸口/反编造/SSE | Guard 策略矩阵 + 无真值不作答 + 白名单外零导出 |
| Phase 4 | 覆盖率/压测/安全审计 | 覆盖率≥80% + 带条件压测报告 + 0 越权 |
| Phase 5 | 镜像/编排/告警/对账/回滚 | staging 全链通过 + 回滚演练报告 + G6 人签 |

## 6. 统一开发工作流（每任务执行顺序）

```
1 认领任务 → 建分支 <type>/<scope>-<desc>         （git.md §2）
2 D 环：核对 ADR/设计，写契约草案
3 C 环：先写契约测试（红）与常量/DDL/OpenAPI 注解
4 I 环：实现 + 单测 + 中文注释（coding.md）
5 V 环：本地 mvn test → 集成(tag) → 契约 → 自检清单
6 提 PR：背景/变更/自检/测试证据/影响面（git.md §3）；文档与代码同 PR
7 reviewer 预审（文件+行号+级别）→ 修复 → 人签（G2/G5/G6）
8 F 环：两次同类 blocker → ADR/standards 反哺 → check_docs
9 阶段末：出口门禁 + 复盘（roadmap.md §5）
```

## 7. 闭环失败处理

| 失败类型 | 处理 |
|---|---|
| 单测红 | 回 I 环修复，禁止跳过 |
| 契约不一致（如 Lua 返回码双口径） | 回 C 环统一枚举，补契约测试（防 09 §F.1 重现） |
| 集成/压测不达标 | 回设计调优（缓存/SQL/线程池），复测并记录条件 |
| 评审 blocker | 落 `NS-G{n}-NNN`，清零才过；同类两次触发 F 环反哺 |
| 门禁未过需推进 | 人签有条件通过 + 登记缺陷，下次门禁前清零 |

## 8. 阶段详细计划索引

| 阶段 | 详细执行方案 |
|---|---|
| Phase 0 | [phases/phase-0.md](phases/phase-0.md) |
| Phase 1 | [phases/phase-1.md](phases/phase-1.md) |
| Phase 2 | [phases/phase-2.md](phases/phase-2.md) |
| Phase 3 | [phases/phase-3.md](phases/phase-3.md) |
| Phase 4 | [phases/phase-4.md](phases/phase-4.md) |
| Phase 5 | [phases/phase-5.md](phases/phase-5.md) |

—— 完 ——
