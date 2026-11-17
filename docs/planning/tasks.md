---
title: NearSlot 任务规划（WBS / 依据开发里程表）
status: current
source_of_truth:
  - ../../docs/planning/tasks.md
updated: 2026-11-16
---

# NearSlot 任务规划（WBS）

> **依据**：[roadmap.md](roadmap.md) Phase 0–5。任务编号 `NS-P{phase}-T{NN}`。
> **规范遵循**：
> - Commit `<type>(<scope>): <中文subject≤50字>`，scope 取 git.md §1 枚举。
> - 分支 `<type>/<scope>-<desc>`（kebab-case）；Squash Merge；禁 force push 共享分支。
> - 每任务标注负责代理（designer / developer / reviewer / tester）与出口门禁。
> - 评审发现编号 `NS-G{n}-NNN`，Blocker 清零才过门禁。
> - 所有交付物落文件（代码/文档/报告），禁止口头结论；跨代理交接附证据。

## 0. 任务总览

| 阶段 | 任务数 | 出口门禁 | 主要代理 |
|---|---|---|---|
| Phase 0 | 6 | G0 | developer / designer / reviewer |
| Phase 1 | 6 | G1+G2 | designer / developer |
| Phase 2 | 8 | G3+G4 | developer / tester |
| Phase 3 | 7 | G3+G4 | developer / tester |
| Phase 4 | 6 | G4 | tester / reviewer |
| Phase 5 | 6 | G5+G6 | developer / reviewer |

## 1. Phase 0 — 治理就绪（G0）

| ID | 任务 | 分支/Commit scope | 代理 | 交付物 | 验收标准 |
|---|---|---|---|---|---|
| NS-P0-T01 | 实现 check_docs.py 四检 | `chore/docs-check` | developer | `scripts/docs/check_docs.py` | frontmatter/链接/可达/source_of_truth 四检全绿，负向用例被拦截 |
| NS-P0-T02 | 建约束登记册 + ArchUnit 基线 | `docs/constraints` | designer | `docs/planning/constraints.md` + ArchUnit 测试 | 依赖铁律（module 禁互依/framework 禁 import module）违规被检测 |
| NS-P0-T03 | 建 GWT 验收矩阵 | `docs/acceptance` | designer+tester | `docs/planning/acceptance-matrix.md` | FR-1~5 与 11 项 SLO 全覆盖，条件标注齐全 |
| NS-P0-T04 | commitlint+husky+CI 骨架 | `ci/setup` | developer | `.husky/` + `ci-cd.yml` | 非法 commit 被拒；CI 五 job 骨架可跑 |
| NS-P0-T05 | .env.example + 密钥扫描 | `chore/security-env` | developer | `.env.example` + 扫描配置 | compose 无明文密钥；缺失变量拒绝启动 |
| NS-P0-T06 | 修正 reviewer README 漂移 | `docs/reviewer-fix` | reviewer | `agents/reviewer/README.md` | 「脚本已实现」改为与事实一致 |

## 2. Phase 1 — 工程搭建（G1+G2）

| ID | 任务 | 分支/Commit scope | 代理 | 交付物 | 验收标准 |
|---|---|---|---|---|---|
| NS-P1-T01 | Maven 九模块骨架 | `feat/common-skeleton` | designer+developer | 根 `pom.xml` + 各模块 pom | revision+flatten 统一版本；`mvn clean install` 绿 |
| NS-P1-T02 | nearslot-common 基础设施 | `feat/common` | developer | 常量/枚举/异常/Result/UserHolder | 单测通过；零业务依赖 |
| NS-P1-T03 | framework 缓存三防 | `feat/cache` | developer | cache 模块（三防+多级+BatchLoad） | 击穿/穿透/雪崩语义测试通过 |
| NS-P1-T04 | framework 锁+幂等 | `feat/framework-lock` | developer | LockTemplate + @Idempotent | Lua 值校验释放；SpEL key 幂等测试通过 |
| NS-P1-T05 | framework 其余能力 | `feat/framework` | developer | ratelimit/bloom/delayqueue/mq/id | 各模块单测通过；延迟队列配对账兜底说明 |
| NS-P1-T06 | Compose + 中间件环境 | `ci/compose` | developer | `docker-compose.yml` + 配置分层 | 一键起全栈；`.env` 强校验生效 |

## 3. Phase 2 — 业务核心（G3+G4）

| ID | 任务 | 分支/Commit scope | 代理 | 交付物 | 验收标准 |
|---|---|---|---|---|---|
| NS-P2-T01 | 双 Token 认证 Lua 族 | `feat/auth` | developer | Token 4 键 + 6 Lua + 双拦截器 | 改密旧 AT ≤1 请求失效；422 并发自愈 |
| NS-P2-T02 | 服务商与时段 | `feat/shop` | developer | GEO + BloomFilter + 时段发布 + 失效广播 | 5km GEOSEARCH；缓存失效广播多实例生效 |
| NS-P2-T03 | 槽位抢约 Lua 内核 | `feat/appointment-lua` | developer | 余量/一人一时段/技师时间轴三检查 + trace | 并发 100 抢 1 余量恰好 1 成功；Lua 返回码单一枚举 |
| NS-P2-T04 | Hold 两段式 + 补偿 | `feat/appointment-hold` | developer | Hold 状态机 + 过期任务 + 支付竞态链 | 超时回补反转（ADR-005）；支付/过期竞态走补偿 |
| NS-P2-T05 | 订单与对账闭环 | `feat/order` | developer | 路由表 + 对账日志 + 回滚失败表 | 对账 ABNORMAL=0；回滚失败告警 |
| NS-P2-T06 | 候补异步提升 | `feat/appointment-waitlist` | developer | ZSet + slot.opened + offer 唯一约束 | exactly-once 提升；jitter 公平排序；不定长取消端点 |
| NS-P2-T07 | 抢约令牌前置闸 | `feat/appointment-token` | developer | 访问令牌 + 独立限流场景 | compare-and-del 原子消费（09b §6） |
| NS-P2-T08 | 支付回调幂等 | `feat/order-pay` | developer | 回调幂等 + 状态机裁决 | 重复回调不重复入账 |

## 4. Phase 3 — AI 集成（G3+G4）

| ID | 任务 | 分支/Commit scope | 代理 | 交付物 | 验收标准 |
|---|---|---|---|---|---|
| NS-P3-T01 | Spring AI 接入 | `feat/agent` | developer | DashScope 配置 + 模型连通 | 调用链 traceId 贯通 |
| NS-P3-T02 | 两阶段规划与编排 | `feat/agent-plan` | developer | TreePlanRouter + MultiRoundOrchestrator | MAX_ROUNDS=5；紧凑规划单次调用 |
| NS-P3-T03 | 双闸口四策略 | `feat/agent-guard` | developer | PromptHookChain + GuardedToolCallback | 高危工具 BLOCK 给理由；配置化无需改码 |
| NS-P3-T04 | 预约确认流程 | `feat/agent-confirm` | developer | bookAppointment 强制 CONFIRM + bypass | TTL 300s；确认后 bypass 执行不二次投票 |
| NS-P3-T05 | L0–L4 反编造 | `feat/agent-truth` | developer | 事实账本 + 输出断言闸 | 无工具真值不作答；入参来自已核实事实 |
| NS-P3-T06 | SSE 流式与重连 | `feat/agent-sse` | developer | 五事件 + 重连状态机 + a11y | meta/plan/progress/error/confirm 齐全；3 次退避重连 |
| NS-P3-T07 | 观测白名单与降级 | `feat/agent-obs` | developer | Langfuse 白名单 + 四级降级 | 白名单外零导出；提示词 Fail-Open |

## 5. Phase 4 — 测试加固（G4）

| ID | 任务 | 分支/Commit scope | 代理 | 交付物 | 验收标准 |
|---|---|---|---|---|---|
| NS-P4-T01 | 测试金字塔补全 | `test/pyramid` | tester | 70/20/10 测试集 | 占比达标；必测清单全过 |
| NS-P4-T02 | Testcontainers 集成 | `test/containers` | tester | Redis/MySQL/RabbitMQ 容器测试 | 容器化集成测试可重复 |
| NS-P4-T03 | Lua 契约测试 | `test/lua-contract` | tester | 返回码单一枚举契约测试 | 09 §F.1 双口径矛盾不再现 |
| NS-P4-T04 | 压测与 SLO | `test/load` | tester | 带条件压测报告 | 抢约 P99≤200ms；TPS≥5000；记录机器/线程/时长/数据量 |
| NS-P4-T05 | 安全审计 | `test/security` | tester+reviewer | 越权/注入/密钥审计报告 | 0 越权；0 明文密钥 |
| NS-P4-T06 | 缺陷分级清零 | `test/defects` | tester | 缺陷台账 | blocker=0；全部分级 |

## 6. Phase 5 — 部署上线（G5+G6）

| ID | 任务 | 分支/Commit scope | 代理 | 交付物 | 验收标准 |
|---|---|---|---|---|---|
| NS-P5-T01 | 镜像构建推送 | `ci/image` | developer | Dockerfile + 镜像 | 镜像可拉取；分层缓存 |
| NS-P5-T02 | 生产编排 | `ci/deploy` | developer | 生产 compose/K8s + 配置分层 | staging 全链路通过 |
| NS-P5-T03 | 监控告警 | `feat/ops-monitor` | developer | Prometheus 告警 + Langfuse | 对账 ABNORMAL>0 立即告警 |
| NS-P5-T04 | 对账任务上线 | `feat/order-reconcile` | developer | 定时对账任务 | 每日对账 0 差异；在途保护窗口生效 |
| NS-P5-T05 | 回滚演练 | `chore/drill` | reviewer+人 | 回滚演练报告 | 回滚成功且记录耗时 |
| NS-P5-T06 | G6 SLO 验收发布 | `docs/release` | reviewer+人 | 验收报告 + tag | SLO 达标；人签；打 `v{semver}` tag |

## 7. 跨阶段规范约束

- **文档与代码同 PR**：ADR 新增/更新必须附决策记录（git.md §3）。
- **反哺闭环**：连续两次同类 blocker → 更新 standards/ 或新增 ADR（review-system.md §4）。
- **代理铁律**：产出必须落文件；跨代理交接带证据（代码/行号/数据/ADR 编号）。
- **防漂移**：每阶段出口跑 `check_docs.py` + ArchUnit + 契约测试，任一红不得过门禁。
