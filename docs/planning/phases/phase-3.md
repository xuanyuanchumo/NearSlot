---
title: Phase 3 详细开发计划（AI 集成 / G3+G4）
status: current
source_of_truth:
  - ../../../docs/planning/phases/phase-3.md
updated: 2026-11-16
---

# Phase 3 详细开发计划：AI 集成（W6–7 / G3+G4）

> 总纲：[development-plan.md](../development-plan.md) · 任务：[tasks.md](../tasks.md) §4 · 研究依据：[09 §A-C/F/G](../../research/09-picknear-verification.md) · 架构：[overview.md §4.3](../../architecture/overview.md)

## 1. 目标与前置

- **目标**：AI 助手可安全代办预约与查询，继承 PickNear 双闸口与 L0–L4 反编造体系，杜绝编造时段/价格。
- **前置**：G4（Phase 2 主链测试）通过——AI 工具依赖业务接口已就绪。
- **出口**：G3 代码评审 + G4 测试评审（Agent 策略专项）；高危工具 BLOCK、白名单外零导出。

## 2. 具体开发步骤（D→C→I）

| 步 | 环 | 动作 | 工件 | 验收 |
|---|---|---|---|---|
| 1 | D/C | 冻结 SSE 五事件契约（meta/plan/progress/error/confirm）+ confirm 恢复接口 + JSON Schema | API 注解 + 事件 schema | 契约测试先红 |
| 2 | I | Spring AI 2.0 + DashScope 接入；traceId 贯通 | agent 配置类 | 调用链同 traceId |
| 3 | C/I | 两阶段规划：TreePlanRouter 紧凑目录（一次 plannerCall）+ MultiRoundOrchestrator（MAX_ROUNDS=5） | 规划器类 | 单次调用定工具+参数；无命中跳过规划 |
| 4 | I | 双闸口：PromptHookChain 前置（BLOCK>REPLACE>PLANNING>PASS）+ GuardedToolCallback 四策略投票 | guard/hook 类 | 4 策略投票矩阵通过 |
| 5 | C/I | bookAppointment 强制 CONFIRM：加入 confirm-tools（配置驱动）+ confirm TTL 300s + bypass 恢复（异步显式传 userId） | 确认流程类 | 高危确认→bypass 不二次投票 |
| 6 | I | L0–L4 反编造：ToolEvidence 旁路登记 + 数据意图兜底 + 输出断言闸 + 事实账本 | truth 子包 | 无工具真值不作答；入参来自已核实事实 |
| 7 | I | SSE 流式：五事件 + Last-Event-ID 增量 + 3 次退避重连 + 流式 a11y | SSE 处理器 | 断线重连正确；事件可 JSON 化 |
| 8 | I/V | 观测白名单（nearslot.agent./spring.ai./gen_ai.）+ 提示词四级降级 Fail-Open | 观测配置 | 白名单外零导出；降级不崩 |

## 3. 落地方案

- **规划默认路径**：按 TreePlanRouter 实证（[09 §F.3](../../research/09-picknear-verification.md)），**不照抄** UNKNOWN/UNCERTAIN 保底重跑说法（默认路径无此机制）。
- **两级截断分离**：摘要压缩 ≤80 字（SubTaskProperties.compressLength）≠ 工具结果 maxResultChars=1200（[09 §F.4](../../research/09-picknear-verification.md)），配置分层不可混。
- **反编造映射**：AI 只能引用工具查得的时段/价格/库存作答；bookAppointment 入参须来自 L4 事实账本（[09 §G.1](../../research/09-picknear-verification.md)）。
- **429 退避**：`5000*2^(attempt-1)`ms，错误回喂 LLM（StreamingChatInvoker 实证）。
- **会话污染防护**：异步段 finally closeRootScope，防 Tomcat 线程复用跨会话；会话归属 conversationId+userId 双过滤。

## 4. 闭环方案

| 环 | 验证动作 | 通过标准 |
|---|---|---|
| V-1 | Guard 四策略投票矩阵（BLOCK/CONFIRM/ALLOW/ABSTAIN） | 高危 BLOCK 给理由；无限流误放行 |
| V-2 | 确认流程：删除类指令 BLOCK、确认类进 confirm（TTL 300s）、超时拒绝 | 全流程断言 |
| V-3 | 反编造：无工具真值询问价格/时段 | AI 拒答或标注未核实，不编造 |
| V-4 | SSE：五事件推送 + 断线 Last-Event-ID 增量补发 | 无重复/无丢失 |
| V-5 | 白名单：制造 @Scheduled span | 白名单外零导出；Langfuse 配额无异常增长 |
| F | 策略缺陷 → 补 Guard 策略单测或修 PromptGuardProperties；同类两次 blocker 反哺 standards | 配置与代码一致 |

## 5. DoD Checklist

- [ ] 两阶段规划 + 多轮编排（≤5）测试绿
- [ ] 双闸口四策略投票矩阵全绿；0 越权
- [ ] bookAppointment 强制 CONFIRM + bypass 验证
- [ ] L0–L4 反编造验证；无真值不作答
- [ ] SSE 五事件 + 重连验证
- [ ] 白名单外零导出；提示词降级 Fail-Open
- [ ] G3+G4 通过；Blocker=0

## 6. 风险与回退

| 风险 | 缓解 |
|---|---|
| 规划链路复杂 | 默认 TreePlanRouter 单调用路径；FeatureProperties 可切 Legacy 运行时（[09 §G.2](../../research/09-picknear-verification.md)） |
| AI 编造时段引发客诉 | L0–L4 强约束 + 输出断言闸，无真值不作答 |
| SSE 断线重复确认 | Last-Event-ID + confirmId 幂等 |
| Langfuse 配额失控 | 白名单 + 配额告警（quality-attributes §3） |
