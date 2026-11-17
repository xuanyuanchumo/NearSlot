---
title: 质量治理深度研究：需求覆盖 · 设计约束 · 评审验收 · 中文注释 · 文档完整性 · 工程化防漂移
status: current
source_of_truth:
  - 12-quality-governance-deep.md
updated: 2026-11-16
---

# 研究报告 12：NearSlot 质量治理深度研究

> 定位：本轮 21 维研究矩阵的收口文档（承载维度 1/2/3/14/15/20，见 [00 覆盖矩阵](00-coverage-matrix.md)）。前序报告回答"抄什么、差在哪"（04/06/09），本文回答"如何把质量治理变成可验收、可拦截、防漂移的工程闭环"。
> 证据边界：NearSlot 处于开发前研究准备期，源码尚未实现（根 README.md:12-14；ops_glob 核验 NearSlot 无 Java/POM/CI 文件）。因此 file:line 证据全部来自文档；运行期数字来自 PickNear 源码实证（经 09 逐行复核）与 hmdp-plus 质量评审文档，凡引用均标注条件，未在当前代码复测的数字标「未复测」。

## 0. 结论先行（六维健康度总表）

| 维度 | 现状 | 一句话结论 | 首要缺口 |
|---|---|---|---|
| 1 功能+非功能覆盖 | 部分 | FR-1~5 与 11 项 SLO 已成型，但验收标准仅 3 条抽样 | 全量 GWT 验收矩阵 + SLO 可测化 |
| 2 设计约束 | 部分 | 13 条 ADR + 依赖铁律齐全，但约束散落、无自动检测 | 约束登记册 + ArchUnit |
| 3 评审与验收 | 有冲突 | Gate 模型 G0~G6 / G1~G6 两套表述并存，DoD 未定义 | 统一 Gate + HITL + DoD |
| 4 中文注释 | 缺口 | coding.md:14 仅两行，无 Javadoc 模板/漂移防护/双语表 | 成体系注释标准 |
| 5 文档完整性 | 部分 | 导航齐全但 source_of_truth 普遍自指、check_docs 未实现 | 语义纠偏 + 脚本 + 目录同步 |
| 6 工程化防漂移 | 缺口 | git/doc/coding 规范完备但 CI/commitlint/版本同步均无实现 | 规范→脚本→CI 闭环 |

总判定：NearSlot 的规范文本质量高于 hmdp-plus（无文档治理、测试仅 3 文件，见 hmdp-plus 15 §1），但执行载体（脚本/CI/代码）全部缺位，处于「纸面生产级」。Phase 1 第一优先级不是再写文档，而是把六维验收条件落成可失败的门禁。

---

## 1. 功能性需求 + 非功能性需求覆盖审计

### 1.1 现状：FR-1~FR-5 覆盖与验收（文件：NearSlot/docs/quality/functional-requirements.md）

| 需求 | 条目（file:line） | 现有验收 | 评级 | 缺口 |
|---|---|---|---|---|
| FR-1.1~1.4 | 验证码/密码登录、双 Token 自愈、改密踢出+等级（:12-15） | 仅 FR-1.3 抽样（:38）：改密后旧 AT ≤1 请求失效 401+X-Auth-Reason=superseded | 部分 | 1.1 频控 60s/原子消费、1.2 防爆破、1.4 等级门槛无 GWT |
| FR-2.1~2.3 | 服务商/时段/余量/GEO 5km（:18-21） | 无 | 部分 | BloomFilter 误判率、失效广播时延、GEO 边界未量化 |
| FR-3.1~3.4 | 抢约/候补/取消回补/延迟提醒（:24-28） | 仅 FR-3.1 抽样（:37）：100 并发同抢 1 余量→1 成功 99 失败，ABNORMAL=0 | 部分 | 未覆盖 Hold 两段式与技师时间轴不重叠（08 §3-4 新增正确性） |
| FR-4.1~4.3 | 订单状态机/支付回调幂等/热门榜（:31-34） | 无 | 部分 | 支付回调与 hold 超时竞态（08 §4）无用例 |
| FR-5.1~5.3 | 自然语言/SSE 确认/会话压缩（:36-39） | 仅 FR-5.2 抽样（:39）：删除类 BLOCK、confirm TTL 300s | 部分 | ≤5 轮、压缩保真、L0-L4 反编造（09 §G.1）未进验收 |

### 1.2 现状：SLO 表（NearSlot/docs/quality/quality-attributes.md:11-22）

| 指标（行号） | 目标 | 实证可信度 | 可测性缺口 | 建议验收条件 |
|---|---|---|---|---|
| 空接口 TPS（:12） | ≥5000 | PickNear 自述 5500，未复测 | 无硬件/并发模型 | 固定 4C8G、wrk 60s、并发 200，TPS≥5000 |
| 抢约 P99（:13） | ≤200ms | 设计目标，未测 | 未定义是否含鉴权/MQ | 端到端含鉴权+Lua+事务后发送，采样≥10k |
| 拦截器（:14） | ≤1ms | PickNear ↓50ms | 与 04 §4「<5ms」、:27「>100ms 告警」三处冲突 | 统一为命中≤1ms/未命中≤5ms/>100ms 告警 |
| 可用性（:15） | 99.9% | 运维门禁 | 无 RPO/RTO 与故障演练 | 月度可用率≥99.9%；季度主从切换 RTO≤5min |
| 超卖/对账（:16-17） | 0 / ABNORMAL=0 | hmdp 闭环实证 | 无观察窗口 | 100 并发同抢，24h 内 ABNORMAL=0 |
| 消息不丢（:26） | — | ADR-008 | 10s 延迟门未进 SLO | 延迟 10s+1s 不误回滚、+2s 才回补（tester:41 草案） |
| Token 失效（:18） | 100% | PickNear 实证 | 无判定时点 | 改密后旧 AT ≤1 请求 401+reason |
| 工具越权（:19） | 0 | PickNear 实证 | 未覆盖 bookAppointment 强制 CONFIRM | 4 Policy 投票矩阵全绿 |
| 覆盖率（:21） | ≥80%/核心85% | CI 门禁（未实现） | JaCoCo 未接 | CI 强制 check 失败即阻断 |
| trace 覆盖（:22） | 100% | 白名单 ADR-011 | 白名单集合未列 | 固定 nearslot.agent./spring.ai./gen_ai. |
| 成本（:22） | ≤1.2× | PickNear ~57% | 无预算告警 | 单会话 token 增幅≤1.2×，超限告警 |

### 1.3 差距分析与建议
根因：① functional-requirements.md:36 自述「抽样，全量随里程碑细化」但 00 矩阵 #1 已标 ✅，状态与实际不符；② 08（2026-11-16）新增 7 条特色域（08 §10）未回写 2026-09-05 的需求文档；③ 阈值三处冲突。
建议与验收：A1 在 functional-requirements.md 增「验收矩阵（GWT）」，15+FR 条目 100% 有 Given/When/Then+SLO ID+测试文件，08 §10 七条映射为 FR-6；A2 以 quality-attributes.md 为 SLO 唯一事实源，grep 同指标仅 1 阈值；A3 11 项 SLO 全带条件或「未复测」（依 standards/doc.md:29）。

---

## 2. 设计约束（ADR、架构规则、技术/团队约束）

| 类别 | 载体（file:line） | 内容 | 执行强度 |
|---|---|---|---|
| ADR | decisions.md（ADR-001~016） | 模块化单体/双Token/框架五件套/Lua+RabbitMQ+对账/回补反转/分片/ID/MQ/延迟队列/Agent双闸口/白名单/精度/密钥 | 文档级；历史条目禁改（:7） |
| 架构铁律 | architecture/overview.md:53 | module 间禁互相依赖；framework 禁 import module；app 除外 | 无自动检测 |
| 技术栈 | overview.md §2 | JDK21/Boot4.x/MP3.5.16/Redis7+Redisson/RabbitMQ/ShardingSphere5.3.2/Spring AI2.0.1 | 无版本锁（00 #10 标红） |
| 编码硬约束 | coding.md:11-33 | 方法≤60行/类≤400行/圈复杂度≤10；线程池/锁/键铁律；事务内禁 MQ | Checklist（:35-37） |
| 移植修正 | 09 §F/§G | Lua 单枚举/4键模型/两级截断/L0-L4/事务后发送 | 散落研究文档 |

差距：约束分散 6+ 文件无单一入口；无 ArchUnit 守护（hmdp-plus「依赖无环」也只人工评审，见 13 §3）；团队约束缺"何时升 ADR/何时人签"（HITL 见 §3）；overview.md:21 Boot「4.x 目标4.2」未锁精确版本。
建议：B1 新建 architecture/constraints.md 约束登记册（ID/类别/出处/违反示例/检测方式/豁免），13 ADR+铁律+09 §F 六条 100% 登记；B2 ArchUnit 负向测试，CI 依赖违规=0；B3 版本锁+四端一致性脚本；B4 blocker 复发必须回补 ADR/standards 并留 commit 引用。

---

## 3. 评审与验收（Gate、五维、HITL、代理对齐、DoD）

### 3.1 Gate 编号冲突（最高优先级）

| 表述 | 出处 |
|---|---|
| 六门禁 G1–G6 | review-system.md:9-21 |
| 六门禁但阶段错位（G3设计/G4代码/G5测试/G6发布） | 04 §3.1:57-64 |
| G0~G6（7 门禁）但全文无 G0 定义 | 根 README.md:30；00 矩阵:31 |
| 流程六门禁 | agents/overview.md:20-27 |

reviewer/README.md:6 声称评审 G2/G3/G5/G6，与 overview.md:26 的 G3/G4 预审不一致；即 docs 内部先冲突。

### 3.2 建议统一 Gate 模型（采纳 G0~G6，以 ADR-014 记录）

| Gate | 阶段 | 通过条件 | 执行者 | HITL |
|---|---|---|---|---|
| G0 治理就绪 | Init | check_docs 可用+约束册/验收矩阵/门禁脚本已建+四代理配置齐 | lead+人 | 人必签 |
| G1 需求 | Plan | FR 全量 GWT+SLO 条件标注齐 | designer+人 | 人抽检 |
| G2 架构设计 | Design | 依赖无环（ArchUnit）+新决策有 ADR+接口表过规范 | designer+reviewer+人 | 人必签 |
| G3 代码 | Build | coding.md 全项+中文注释+blocker=0 | reviewer 代理 | 代理预审+人抽检 |
| G4 测试 | Build | 覆盖率≥80%/核心85%+必测清单全过+Lua 语义绿 | tester 代理 | 缺陷分级人确认 |
| G5 发布 | Deploy | 检查单+.env 完整+回滚方案+灰度+对账上线 | reviewer+人 | 人必签 |
| G6 验收 | Maintain | SLO 达标+用户旅程实测+带条件压测报告 | 代理组+人 | 人必签 |

### 3.3 五维/DoD/代理对齐

| 项 | 现状 | 缺口 | 验收标准 |
|---|---|---|---|
| 五维检查单 | review-system.md:23-27；reviewer:14-30 | 04 §3.2 另一套五维（功能/非功能/约束/UX/工程化）并存 | 以 review-system 五维为门禁唯一版，04 改称研究对比五维 |
| 评审记录 | review-system.md:29-31；04 §3.3 NS-G4-012 编号 | 无 review/G{n}-{模块}-{日期}.md 模板 | 每次门禁产出编号发现项+级别+责任人+验证方式；blocker 清零才放行 |
| HITL | review-system.md:17/19/21 | 签字点/否决权未逐项列 | 固化 §3.2 四个强制人签点；争议走 ADR（:30 原则） |
| 代理对齐 | overview.md:7-14 + 4 个 agents README | Gate 号不一致；reviewer:47 称 check_docs「已实现前六项」但脚本不存在 | 修正 Gate 号；「已实现」改「规范已定义，Phase1 实现」 |
| DoD | hmdp 13 §1.3 有功能 DoD；NearSlot 仅 testing/strategy.md:38-41 基线 | 无成文 DoD | 新建 quality/definition-of-done.md：功能/代码/测试/文档/发布五类 DoD |

---

## 4. 中文注释

| 来源 | 条款 | 评级 |
|---|---|---|
| coding.md:14 | 公共 API 与复杂逻辑必须中文 Javadoc（说明为什么）；类头注明来源参考 | 部分（仅两行） |
| overview.md:35 | 术语首现给英文 | 部分（仅正文） |
| 00 矩阵:32 | 维度15「需成体系（Javadoc/注释漂移）」 | 缺口 |
| 09 §F.5 | PickNear RefreshExpiredToken.lua:8 注释 KEYS[3]=token:version，实际传 LOGIN_VALID_VERSION_KEY——漂移实证 | 反面教材 |

建议写入 coding.md §6：①类头写职责+来源参考+约束；②公有方法 Javadoc 含 @param/@return/@throws 与「为什么」（并发/一致性强制）；③复杂逻辑写 Why-not；④Lua 头注释 KEYS/ARGV 契约必须与 Java 调用方一一对应，返回码只引枚举名（禁止魔法数字）；⑤禁复述代码、纯英文注释、无主 TODO。
漂移三防线（验收）：契约测试（仿 PickNear WriteGuardConsistencyCheckTest，见 06 §3）CI 漂移数=0；reviewer 将注释与实现不符记 major；新建 standards/terminology.md 固定术语（slot 槽位/hold 持有/waitlist 候补/offer 提升邀约/dual-gate 双闸口/reconciliation 对账），新词未登记=0。

---

## 5. 架构与设计文档完整性

| 域 | 现状 | 需修正 | 证据 |
|---|---|---|---|
| 导航 | docs/README.md:9-21、根 README:16-37 | 导航仅登记 01-08；09/10/11/12 未登记，00 §2 承诺未兑现 | ops_glob 实有 00-09 十文件 |
| 需求/质量 | 均 updated 2026-09-05 | 需同步 08/09 与 SLO 统一 | §1.3 |
| 评审 | review-system.md 39 行 | Gate 冲突、DoD 缺失、五维双版本 | §3 |
| 文档规范 | doc.md:22-27 定义 check_docs 四项 | 脚本未实现（根 README:41）；缺第7项检查单 ID（reviewer:47） | 待办 |
| ADR | decisions.md 13 条 | 新增 ADR-014；修 overview.md:92「12 条」 | grep 实证 |
| 运维 | deployment/performance/security | 无 runbooks/ 独立目录（06 §6 建议） | 06 §4 |

source_of_truth 审计（关键缺陷）：PickNear 规范（md/规范/文档规范.md:51-56）要求指向真实源码且被 Git 跟踪，06 §1.3 强调「指向源码是关键设计」；NearSlot 质量/规范/研究文档普遍自指，如 functional-requirements.md:5-7 指向自己，00/08/09 同样自指；check_docs 未实现故无校验；09/12 未登记 README 违反「current 必须从 README 可达」；ADR 数 13 vs 12、Gate 两套、阈值三冲突已发生。
建议：C1 自指改为真实依据（研究依据 + source_kind: research，落地后改源码）；C2 实现 check_docs.py 六检查+第7项检查单ID+第8项验收矩阵ID，负向测试可拦截；C3 目录登记 00/09/10/11/12 可达率100%；C4 漂移巡检 0 conflict 纳入 CI。

---

## 6. 工程化规范与漂移风险

| 规范项 | NearSlot 文档 | 落地 |
|---|---|---|
| Commit | git.md:5-15（type10/scope表/中文≤50/body≤72/footer） | 无 commitlint/husky |
| 分支 | git.md:17-22（main保护+develop+hotfix+Squash+禁 force push） | 仅文档 |
| PR | git.md:24-27 | 仅文档 |
| 版本 | git.md:29-31（revision+flatten+tag+CHANGELOG） | 无 pom |
| CI/CD | reviewer:49-51（changes→ci→build-image→notify，tag sha，ACR provenance 关闭）；deployment:33 | 无 workflow |
| 测试入 CI | testing:13,26；tester:46-47（@Tag integration） | 无 surefire |
| 密钥 | ADR-013 decisions:85-89 + deployment:29 + security:39 | 无 .env.example/compose |
| 文档门禁 | doc.md:22-27 + docs/README:34 | 脚本待实现 |

漂移风险：R1 规范-执行脱节（写「CI必须绿」却无 CI）；R2 文档腐烂（未登记/README 冲突/注释漂移实证）；R3 数字漂移（PickNear 数字当 NearSlot 实测，doc.md:29 已警告）；R4 密钥配置漂移（.env.example 与变量不同步）；R5 依赖版本漂移（Boot 4.x 未锁）。
建议与验收：D1 Phase1 四件套（check_docs.py + commitlint/husky + CI 五 job + .env.example/密钥扫描），3 例负向测试全部被拦截；D2 建 docs/runbooks/（TL;DR 风格，命令/键名/检查单 ID 真实存在）；D3 规范变更双写（standards/ADR 变更同 PR 更新 README 与代理 README，review-system:31 闭环）；D4 建 quality/tech-debts.md 按 06 §5 格式登记本文六项债，G0/G6 逐条对照。

---

## 7. 落地路线图（供 lead 汇总）

| 优先级 | 动作 | 维度 | Gate | 产出 |
|---|---|---|---|---|
| P0 | 统一 Gate+ADR-014+修 ADR 数/五维双版本 | 3,5 | G0 | review-system 修订+ADR-014 |
| P0 | 全量 FR 矩阵+FR-6 特色域+SLO 单一化 | 1 | G1 | functional-requirements §4 |
| P0 | check_docs.py+目录登记+source_of_truth 纠偏 | 5,6 | G0 | 脚本+0 conflict |
| P1 | 约束清单+ArchUnit+版本同步 | 2 | G2 | constraints.md+CI job |
| P1 | 中文注释标准+术语表+Lua 契约测试 | 4 | G3 | coding.md §6+守护测试 |
| P1 | DoD+评审模板+HITL 签字点 | 3 | G2/G5/G6 | definition-of-done.md |
| P2 | commitlint+CI 拓扑+.env.example+runbooks+债务台账 | 6 | G0/G5 | 工程化四件套 |

## 8. 证据索引
NearSlot：README.md:12-14/29/30/41；docs/README.md:9-21/31-35/39-52；quality/functional-requirements.md:12-39；quality/quality-attributes.md:11-33；quality/review-system.md:9-31；standards/coding.md:11-37、doc.md:22-29、git.md:5-31；architecture/decisions.md:7,9-89、overview.md:20-29,53,86-92；agents/overview.md:7-35 与 agents/*/README.md；research/00,04,06,08,09。
外部证据：PickNear/md/规范/文档规范.md:51-56、md/规范/git规范.md §1-8、md/README.md、md/agent/runbook/快速开始与配置.md §2-6；hmdp-plus/13 §〇/§1.3/§2.1/§6/§7、15 §1/§5/§6、16 §1/§2/§4。

—— 完 ——
