---
title: NearSlot 开发里程表
status: current
source_of_truth:
  - ../../docs/planning/roadmap.md
updated: 2026-11-16
---

# NearSlot 开发里程表（Phase 0–5 / 门禁 G0–G6）

> **权威来源**：本表取代 [`学习.md` §第五部分](../../../学习.md) 的旧里程碑表述（旧表存在门禁编号漂移，已更正，见 §2）。
> **配套**：任务分解见 [tasks.md](tasks.md)；门禁定义见 [quality/review-system.md](../quality/review-system.md)。
> **原则**：每阶段有唯一出口门禁；Blocker 清零才过（编号 `NS-G{n}-NNN`）；未完成前置门禁不得进入下一阶段。

## 1. 总览里程表

| 阶段 | 周期 | 主题 | 核心交付 | 出口门禁 | 前置 |
|---|---|---|---|---|---|
| **Phase 0** | W0（前置） | 治理就绪 | check_docs.py / 约束册 / 验收矩阵 / CI 骨架 / .env.example | **G0 治理就绪**（脚本全绿+人签） | 研究报告 00–12 ✅ |
| **Phase 1** | W1–2 | 工程搭建 | 九模块 Maven 骨架 + framework 五件套 + Compose | **G1 需求评审 + G2 设计评审** | Phase 0 |
| **Phase 2** | W3–5 | 业务核心 | 认证 / 服务商 / 预约槽位 / Hold / 候补 / 订单对账 | **G3 代码评审 + G4 测试评审（Lua 语义）** | Phase 1 |
| **Phase 3** | W6–7 | AI 集成 | 两阶段规划 + 双闸口 + L0–L4 反编造 + SSE + Langfuse | **G3 代码评审 + G4 测试评审（Agent 策略）** | Phase 2 |
| **Phase 4** | W8 | 测试加固 | 覆盖率≥80% / 压测（记录条件）/ 安全审计 | **G4 测试评审（SLO 达标）** | Phase 2, 3 |
| **Phase 5** | W9 | 部署上线 | CI/CD + 监控告警 + 对账任务 + 回滚演练 | **G5 发布评审 + G6 验收评审**（人签） | Phase 4 |

## 2. 门禁映射更正说明（消除漂移）

研究 [12 §3](../research/12-quality-governance-deep.md) 发现旧 `学习.md` 里程碑表门禁编号与 [review-system.md](../quality/review-system.md) 定义不一致，本里程表统一如下：

| 旧表述（学习.md） | 问题 | 更正后 |
|---|---|---|
| Phase 1 → 「G2 架构评审」 | G2 正式名为「设计评审」（架构在 G2 内审） | G2 设计评审（含架构）+ 补 G1 需求评审 |
| Phase 2 → 「G3 设计评审」 | G3 实为「代码评审」；Lua 语义测试属 G4 | G3 代码评审 + G4 测试评审 |
| Phase 3 → 无门禁 | 缺门禁 | 补 G3 + G4（Agent 策略专项） |
| Phase 4 → 「G5 测试评审」 | G5 实为「发布评审」；测试评审是 G4 | G4 测试评审 |
| Phase 5 → 「G6 发布评审」 | G6 实为「验收评审」；发布评审是 G5 | G5 发布评审 + G6 验收评审 |
| 无 Phase 0 / G0 | 「纸面生产级」——脚本与 CI 载体缺位 | 新增 Phase 0 → G0 治理就绪 |

> 各门禁确切定义见 [review-system.md §1](../quality/review-system.md)：G0 治理就绪 / G1 需求 / G2 设计 / G3 代码 / G4 测试 / G5 发布 / G6 验收。

## 3. 阶段详述

### Phase 0 — 治理就绪（W0）

- **目标**：把规范从"纸面"变成"可执行门禁"，消除研究 [12](../research/12-quality-governance-deep.md) 指出的漂移。
- **范围**：`check_docs.py` 四检（frontmatter / 链接 / 可达 / source_of_truth）；约束登记册 + ArchUnit 基线；GWT 验收矩阵；commitlint+husky+CI 骨架；`.env.example` + 密钥扫描；修正 reviewer README「脚本已实现」的不实表述。
- **出口标准**：4 项确定性检查在负向用例下全部拦截；FR-1~5 与 11 项 SLO 均有 GWT；人签 G0。
- **关联维度**：3, 5, 6, 10, 14, 15, 20。

### Phase 1 — 工程搭建（W1–2）

- **目标**：可构建、可部署的空骨架，框架能力先于业务落地。
- **范围**：Maven 九模块（revision+flatten，依赖铁律 ArchUnit 固化）；`nearslot-common`；`nearslot-framework`（cache 三防 / lock / idempotent / ratelimit / bloom / delayqueue / mq / id）；Docker Compose + 中间件 + `.env` 强校验。
- **出口标准**：`mvn clean install` 绿；ArchUnit 依赖铁律无违规；compose 一键起全栈；docs 检查绿。
- **关联维度**：5, 7, 8, 18, 19, 20。

### Phase 2 — 业务核心（W3–5）

- **目标**：跑通"找服务商→抢约→支付→履约→对账"主链，达成特色域内核（[research/08](../research/08-nearslot-domain-research.md)）。
- **范围**：双 Token 4 键 + Lua 族 + 双拦截器；GEO + BloomFilter + 时段发布 + 失效广播；槽位 Lua 三检查 + trace；Hold 两段式 + 过期任务 + 支付竞态补偿；订单路由表 + 对账日志 + 回滚失败表；候补 ZSet + 异步提升 + offer；抢约令牌前置闸（[09b §6](../research/09b-hmdp-plus-verification.md)）。
- **出口标准**：并发 100 抢 1 余量恰好 1 成功 99 失败；对账 ABNORMAL=0；Lua 语义测试全绿（含回补反转 ADR-005）；幂等/取消/超时分支测试通过。
- **关联维度**：1, 6, 7, 8, 9, 13, 17, 19。

### Phase 3 — AI 集成（W6–7）

- **目标**：AI 助手可安全代办预约，继承 [09 §G](../research/09-picknear-verification.md) 的反编造体系。
- **范围**：Spring AI 2.0 + DashScope；TreePlanRouter 两阶段规划 + 多轮编排（MAX_ROUNDS=5）；双闸口四策略配置化；`bookAppointment` 强制 CONFIRM + bypass 审批恢复；L0–L4 事实账本；SSE 五事件 + 重连；Langfuse 白名单 + 提示词四级降级。
- **出口标准**：高危工具 BLOCK 且给理由；确认流程 TTL 300s；无工具真值不作答；白名单外零导出；Agent 策略测试全绿。
- **关联维度**：4, 6, 8, 9, 11, 12。

### Phase 4 — 测试加固（W8）

- **目标**：用数据证明质量，杜绝"无条件的压测数字"（[performance/guide.md §5](../performance/guide.md)）。
- **范围**：测试金字塔 70/20/10；Testcontainers（Redis/MySQL/RabbitMQ）；契约测试（Lua 返回码单一枚举，杜绝 [09 §F.1](../research/09-picknear-verification.md) 双口径）；压测（抢约 P99≤200ms、空接口 TPS≥5000，记录机器/线程/时长/数据量）；安全审计；blocker 清零。
- **出口标准**：行覆盖≥80%（核心≥85%）；SLO 全部达标且数字带条件；0 越权；0 未分级缺陷。
- **关联维度**：2, 11, 13, 14, 17。

### Phase 5 — 部署上线（W9）

- **目标**：可发布、可观测、可回滚。
- **范围**：镜像构建 + 仓库推送；生产编排 + 配置分层；Prometheus 告警 + Langfuse 运营；对账定时任务上线；回滚演练；G6 SLO 验收。
- **出口标准**：staging 全链路通过；回滚演练成功并记录耗时；对账每日 0 差异；门禁人签完成。
- **关联维度**：2, 5, 11, 13, 14。

## 4. 关键路径与依赖

```
Phase 0 ─→ Phase 1 ─→ Phase 2 ─┬─→ Phase 3 ─┐
                               └────────────┴─→ Phase 4 ─→ Phase 5
```
- **关键路径**：Phase 0→1→2→4→5（Phase 3 可与 Phase 4 部分重叠，但 G4 总验收需在两者之后）。
- **硬依赖**：Phase 2 的槽位 Lua 依赖 Phase 1 的 framework 锁/幂等；Phase 3 的工具依赖 Phase 2 的业务接口；Phase 5 对账任务依赖 Phase 2 的对账表。
- **缓冲**：Phase 2（3 周）含 1 周一致性闭环缓冲——这是两项目实证中缺陷最密集区。

## 5. 变更控制

- 阶段范围变更必须走 ADR（新增编号，只增不改）+ 更新本表 + 同步 [tasks.md](tasks.md) 与 GWT 矩阵。
- 门禁未过而需进入下一阶段：须人签「有条件通过」并记录 `NS-G{n}-NNN` 缺陷，下次门禁前清零。
- 每阶段结束产出复盘：通用问题反哺 standards/ 或新增 ADR（[review-system.md §4](../quality/review-system.md)）。
