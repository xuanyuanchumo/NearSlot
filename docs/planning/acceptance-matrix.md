---
title: NearSlot GWT 验收矩阵（FR-1~5 + SLO-1~11）
status: current
source_of_truth:
  - ../../docs/planning/acceptance-matrix.md
updated: 2026-11-16
---

# NearSlot GWT 验收矩阵

> **定位**：G1 需求评审与 G6 验收评审的唯一验收清单。每条 Given-When-Then 必须关联 FR 或 SLO，并有验证脚本/测试用例 Id。
> **规则**：无孤儿需求（每条 FR/SLO 至少一条 GWT），无孤儿用例（每条 GWT 关联 FR/SLO）。

## 1. 功能性需求 GWT（FR-1~5）

| ID | Given | When | Then | FR | 验证用例 | 门禁 |
|---|---|---|---|---|---|---|
| GWT-FR-1.3 | 用户已登录且 AT 有效 | 修改密码 | 旧 AT ≤1 请求内失效，401 + X-Auth-Reason: superseded | FR-1.3 | NS-TC-AUTH-005 | G4 |
| GWT-FR-1.3b | 两并发刷新请求携带同一旧 RT | 同时刷新 | 仅一个成功，另一个 422 并发自愈拿到当前凭证 | FR-1.3 | NS-TC-AUTH-006 | G4 |
| GWT-FR-2.2 | 时段余量缓存存在 | 后台发布时段变更 | 多实例 Caffeine 均失效（广播消费） | FR-2.2 | NS-TC-SHOP-001 | G4 |
| GWT-FR-2.3 | 用户坐标在服务商 5km 内 | GEOSEARCH | 返回服务商按距离排序 | FR-2.3 | NS-TC-SHOP-002 | G4 |
| GWT-FR-3.1 | 时段余量=1，100 用户并发抢 | 同时提交预约 | 恰好 1 成功、99 失败（50101/50102），对账 ABNORMAL=0 | FR-3.1 | NS-TC-APPT-001 | G4 |
| GWT-FR-3.1b | 同一用户已完成该时段预约 | 再次预约同一时段 | 返回 50102 重复预约，余量不变 | FR-3.1 | NS-TC-APPT-003 | G4 |
| GWT-FR-3.1c | 技师时段 A 已约 [10:00,11:00) | 新预约 [10:30,11:30) | 时间轴重叠检测拒绝（新增特色约束） | FR-3.1 | NS-TC-APPT-004 | G4 |
| GWT-FR-3.2 | 时段已满且有候补 ZSet | 用户取消预约 | 异步提升队首候选，取消端点不阻塞，exactly-once | FR-3.2 | NS-TC-WAIT-007 | G4 |
| GWT-FR-3.3 | 预约状态 CONFIRMED | 用户取消 | 状态→CANCELLED，Redis 回补反转 before/after，trace=RESTORE | FR-3.3 | NS-TC-APPT-002 | G4 |
| GWT-FR-3.4 | 开抢前 2 分钟 | 延迟队列到期 | 提醒去重窗口 300s 内仅发一次 | FR-3.4 | NS-TC-APPT-005 | G4 |
| GWT-FR-4.1 | 订单已分片落库 | 用 orderId 查询 | 路由表直查成功，零二次计算 | FR-4.1 | NS-TC-ORDER-001 | G4 |
| GWT-FR-4.2 | 支付回调重复到达 | 第二次回调 | 幂等跳过，不重复入账 | FR-4.2 | NS-TC-ORDER-002 | G4 |
| GWT-FR-5.2 | 用户发删除类指令 | Agent 解析 | 工具被 BLOCK 并给出理由 | FR-5.2 | NS-TC-AGENT-001 | G4 |
| GWT-FR-5.2b | 用户发预约类指令 | bookAppointment 触发 | 进入 confirm（TTL 300s），确认后 bypass 执行不二次投票 | FR-5.2 | NS-TC-AGENT-002 | G4 |
| GWT-FR-5.2c | 无工具真值来源 | 用户问"某时段价格" | AI 拒答/标注未核实，不编造 | FR-5.1 | NS-TC-AGENT-003 | G4 |

## 2. 非功能需求 GWT（SLO-1~11）

> SLO 编号对应 \`docs/quality/quality-attributes.md\` §1 表（按行顺序 1~11）。

| ID | SLO | Given | When | Then（目标） | 验证脚本 | 条件标注 | 门禁 |
|---|---|---|---|---|---|---|---|
| GWT-SLO-01 | 空接口 TPS | 独立压测环境 | wrk 压空接口 60s | ≥5000 TPS | 压测报告 | 机器/线程/数据量/拓扑/JDK 必记 | G4/G6 |
| GWT-SLO-02 | 抢约 P99 | 并发抢约场景 | 压测 60s | ≤200ms | NS-TC-LOAD-001 | 同上 | G4/G6 |
| GWT-SLO-03 | 拦截器增量 | Caffeine 命中 | 压测拦截器 | ≤1ms（未命中<5ms，>100ms 告警） | 压测报告 | 条件必记 | G4 |
| GWT-SLO-04 | 可用性 | 生产监控 | 月度统计 | ≥99.9% | 监控报表 | 统计周期注明 | G6 |
| GWT-SLO-05 | 抢约超卖 | 并发抢约 | 对账任务运行 | 超卖=0，ABNORMAL=0 | NS-TC-APPT-001/RECON-008 | — | G4/G6 |
| GWT-SLO-06 | 对账差异 | 每日对账 | 定时任务执行 | 差异数=0，出现即告警 | NS-TC-RECON-008 | — | G6 |
| GWT-SLO-07 | Token 主动失效 | 改密/顶替 | 旧 AT 请求 | 成功率 100% | NS-TC-AUTH-005 | — | G4 |
| GWT-SLO-08 | 高危工具越权 | Guard 策略全开 | 越权攻击用例 | 越权次数=0 | NS-TC-AGENT-001 | — | G4 |
| GWT-SLO-09 | 行覆盖率 | CI 执行 JaCoCo | 生成报告 | ≥80%（核心≥85%） | JaCoCo 报告 | 统计口径注明 | G4 |
| GWT-SLO-10 | trace 覆盖 | 白名单内链路 | Langfuse 采样 | 100% 覆盖；白名单外零导出 | 观测测试 | — | G4 |
| GWT-SLO-11 | Agent token 增幅 | 多轮会话 | 对比单轮 | ≤1.2×（O(1) 压缩） | 压缩测试 | 会话样本注明 | G4 |

## 3. 追溯完整性检查

| 检查 | 规则 | 当前状态 |
|---|---|---|
| FR 覆盖 | FR-1.1~FR-5.3 每条至少 1 条 GWT | 已覆盖关键条目；FR-1.1/1.2/1.4/2.1/4.3/5.3 待 Phase 2/3 补细 |
| SLO 覆盖 | SLO-1~11 每条 1 条 GWT | ✅ 11/11 |
| 用例关联 | 每条 GWT 有 NS-TC-* 或压测报告 | ✅ |
| 条件标注 | 性能类 GWT 必带条件列 | ✅ |
