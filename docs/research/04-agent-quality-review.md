---
title: AI Agent 体系与质量保障/评审体系对比研究
status: current
source_of_truth:
  - ../../docs/research/04-agent-quality-review.md
updated: 2026-11-16
---

# 研究报告 04：AI Agent 体系与全生命周期质量评审体系

## 1. AI Agent 能力对比

| 能力 | PickNear | hmdp-plus | NearSlot 目标 |
|---|---|---|---|
| 规划 | 两阶段（Phase1 决策 + PlanRouter 紧凑目录规划） | 无 | 继承两阶段 + DAG 执行 |
| 工具安全 | 双闸口（PromptHookChain + GuardedToolCallback 4 Policy + 数据权限 AOP） | 无 | 继承 + 增加"预约/支付类工具强制 CONFIRM" |
| 上下文治理 | 摘要压缩 ≤80 字(SubTaskProperties.compressLength) + 工具结果截断 maxResultChars=1200(两级勿混淆) + 子 agent O(1) + 对话级异步压缩（P2） | 无 | 继承全套 |
| 提示词 | Langfuse → 本地 DB → 内置模板 → Fail-Open 四级降级 | 无 | 继承四级降级 |
| 观测 | Micrometer→OTel→OTLP→Langfuse，白名单放行 agent./spring.ai./gen_ai. | 仅 prometheus 指标 | 继承 + 预约业务 span（plan/booking/cancel） |
| 历史会话 | 自建 agent_conversation/agent_message，决策分支落库，best-effort | 无 | 继承 |
| 成本 | 观测白名单防配额耗尽（@Scheduled 2s 任务实测 21.6 万 units/月）；工具路由减少 prompt 长度 | — | 继承 + token 预算告警 |

### PickNear Agent 八大难点（NearSlot 设计输入）

1. 安全闸口前置+执行双保险；@TargetTool 自动注册自动包 Guard（防人工漏包）。
2. 两阶段规划三层校验（JSON 语法/工具存在/历史状态）；终结状态部分失败不阻塞。
3. 真流式直连 model 层（绕过 ChatClient 观察 context 污染）；contextWrite 显式传父保 traceId。
4. 异步线程登录上下文：toolContext 显式传递，不依赖 ThreadLocal。
5. span 生命周期时序（createNotStarted→openScope→start 顺序不可错）。
6. 业务历史表 (conversation_id,user_id) 归属校验；PLANNING 回合只落最终答案。
7. 工具路由紧凑目录 + 一次调用完成选工具+定参数（默认 TreePlanRouter 无 UNKNOWN/UNCERTAIN 保底重跑，见 09 §F.3）。
8. 成本治理：结果压缩、O(1) 上下文、观测白名单。

**NearSlot Agent 工具清单设计**：querySlots（查询时段）、recommendShops（推荐）、bookAppointment（**CONFIRM 必确认**）、cancelAppointment（**CONFIRM**）、queryOrder、subscribeSlot（候补订阅）、topTechnicians。高危名单：delete*/batch* 直接 BLOCK；参数含 admin/.*delete.*/ 正则 CONFIRM。

## 2. 质量保障体系对比

| 维度 | PickNear | hmdp-plus | NearSlot 目标 |
|---|---|---|---|
| 测试规模 | 102 测试类（Agent 域密度最高） | 3 个 | 金字塔全覆盖（见 testing/strategy.md） |
| 单元测试对象 | 4 Guard 策略/3 Hook/压缩/账本/路由 | 无 | Lua 语义、策略投票、状态机 |
| 集成测试 | 4 类被 CI 排除（TokenTest/AgentTracer/LangfuseSmoke/ContextLoads） | 无 | Testcontainers MySQL/Redis/RabbitMQ |
| CI | check_docs.py → compile → test（排除集成） → push 才 build 推 ACR → 通知 | 无 CI 文件 | 文档检查→compile→unit→testcontainers→构建推镜像 |
| 文档治理 | frontmatter+source_of_truth+check_docs.py | README 强但无治理 | frontmatter 治理 + docs 检查脚本 |
| Git 规范 | commitlint+husky+scope 表 | 无 | 继承 |
| 观测 | Langfuse 全链路+白名单 | prometheus | Langfuse+prometheus+失败计数器 |
| 可靠性运维 | 死信+幂等+事务后发送 | **对账闭环+回滚失败表+缓存失效广播**（更强） | 取两者并集 |

## 3. 全生命周期评审体系（NearSlot 采纳，吸收两项目文化）

两项目共同点：**评审发现的问题有编号、有修复记录**（PickNear H-3 锁无所有者→LockTemplate 修复；H-5 事务内不发 MQ；S-1 幂等收尾）。NearSlot 将其制度化：

### 3.1 六个评审门禁

| 阶段 | 评审 | 门禁（不通过不放行） |
|---|---|---|
| G1 需求 | 功能性需求评审 | 用户故事+验收标准（Given/When/Then）齐备；非功能指标（QPS/时延/一致性）明确 |
| G2 架构 | 架构评审 | 模块边界/依赖方向/技术选型 ADR 记录；分片与 ID 策略确认 |
| G3 设计 | 详细设计评审 | 时序图（抢约/取消/对账）；Lua 与回补语义评审；缓存键与 TTL 表 |
| G4 代码 | Code Review | reviewer 代理检查单全过；Blocker 清零；覆盖率≥80% |
| G5 测试 | 测试就绪评审 | 冒烟通过；压测基线达标（QPS/P99/零超卖）；安全用例过 |
| G6 发布 | 发布评审 | 回滚方案；灰度开关；对账任务上线确认；观测白名单生效 |

### 3.2 五维检查维度（每次评审覆盖）

1. 功能性：需求覆盖、边界条件、异常分支。
2. 非功能性：性能（QPS/P99）、可用性、一致性（对账闭环）、安全（双 Token/注入/越权）、容量（分片/ID 池）。
3. 设计约束：技术栈锁定（JDK21/Boot4.x/RabbitMQ/Redis7）、编码规范、依赖方向。
4. UX/UI：响应式断点、触控区、加载/空/错误三态、无障碍对比度。
5. 工程化：中文注释（类职责/公有方法 Javadoc/复杂逻辑 Why）、commit 规范、文档 frontmatter、测试覆盖。

### 3.3 评审记录规范

- 每次评审产出 `review/G{n}-{模块}-{日期}.md`：发现项编号（如 NS-G4-012）+ 等级（Blocker/Major/Minor）+ 修复责任 + 验证方式。
- Blocker 未清零禁止进入下一门禁；Minor 允许带病放行但记账。

## 4. 非功能需求基线（NearSlot SLO）

| 指标 | 目标 | 出处/理由 |
|---|---|---|
| 空接口 QPS | ≥5500 | PickNear 实测（2800→5500，Caffeine+批量回填） |
| 抢约接口 P99 | <200ms | Lua 预扣+异步落库 |
| 拦截器开销 | 命中≤1ms / 未命中<5ms（>100ms 告警） | PickNear Caffeine 版本快拒；三处阈值已统一见 quality-attributes.md §1 |
| 库存一致性 | 零超卖（对账闭环兜底） | 两项目压测结论 |
| 消息延迟阈值 | >10s 丢弃回补 | hmdp-plus beforeConsume 门 |
| 可用性 | 99.9%（单区域） | healthcheck+自动重启+watchtower |
| Token 主动失效 | 100% | PickNear 版本号体系 |

## 5. 用户旅程（验收主线）

1. 游客浏览服务列表（GEO 附近）→ 注册登录（短信/密码，双 Token 下发）。
2. AI 助手对话推荐（工具路由→查询时段→推荐）→ 确认预约（CONFIRM 闸口）。
3. 抢约高峰：Lua 预扣→RabbitMQ→落库；失败回补+候补订阅（ZSet）。
4. 开抢前 2 分钟延迟队列提醒；余票回流自动补发最早期候补者。
5. 支付定金（回调验签+幂等）→ 到店核销 → 签到 BitMap → 评价（Feed 推模式）。
6. 取消：状态机→回补（反转语义）→Top 榜回退→候补补发→对账任务巡检。

—— 完 ——
