---
title: 工程生命周期与质量保障深读（PickNear 文档治理/CI/测试/runbook）
status: current
source_of_truth:
  - ../../docs/research/06-engineering-lifecycle-research.md
updated: 2026-11-16
---

# 研究报告 06：PickNear 工程生命周期深读（第二轮）

> 第一轮（04 报告）研究了 Agent 质量与评审体系；本报告补齐"文档治理实现细节、CI 流水线、
> 测试金字塔布局、runbook 文化、诚实债务清单"五个维度，全部来自源文件级阅读。

## 1. 文档治理体系（实现级）

### 1.1 治理入口 md/README.md（47 行）
- 模块入口表 6 域（agent/auth/arch/ops/report/规范），每域一个 README 做任务路由。
- 阅读规则 5 条：①本页选模块 ②模块 README 任务路由 ③**不默认通读** ④代码/配置/运行时/测试为事实源 ⑤历史材料不参与默认阅读。
- 新增约束 5 条：默认不新建文档 → 确认无法承载 → 声明 status+入模块 README → 可检查相对链接 → 修改后跑 check_docs.py。
- status 三态：current（当前有效事实）/ draft / legacy（不进入当前路由）。**历史靠 Git，不建 archive/ 目录**。

### 1.2 check_docs.py（204 行）的 6 项确定性检查
1. frontmatter 必填（README.md 豁免）；
2. status ∈ {draft, current, legacy}；
3. source_of_truth 逐条必须存在**且被 Git 跟踪**（`git ls-files --error-unmatch`）；
4. superseded_by 指向的文档必须存在；
5. README + current 文档内的相对链接必须可达（忽略图片 ![]()、http/https/mailto/tel、#锚点、仓库外路径）；
6. **current 文档必须被所属模块 README 链接；legacy 文档必须不被链接**（双向可达性检查）。
- 输出摘要：`N indexes, N current docs, N legacy docs, N files with frontmatter`，任何错误整体退出码 1 进 CI。
- 设计亮点：把"文档腐烂"变成 CI 可拦截事件；链接可达 + 路由可达双检查保证每篇 current 文档都有入口。

### 1.3 frontmatter 实例（arch/系统架构设计.md）
```yaml
status: current
source_of_truth: [16 个源文件路径]   # 每条指向真实代码/配置
superseded_by:                       # 空字段也必须存在
```
- "source_of_truth 指向源码"是关键设计：文档声明自己的事实边界，读者可 jump-to-code。

## 2. CI 流水线（ci-cd.yml，2026-09-17 版）

### 2.1 Job 拓扑
```
push/PR/workflow_dispatch
  → changes   判定是否含代码变更（git diff --name-only 排除 md/ 与 *.md）
  → ci        check_docs.py → JDK17 temurin → mvn compile → mvn test
  → build-image  needs: ci；仅 push master/手动；buildx 推阿里云 ACR
  → notify    always；飞书/钉钉 webhook（未配置则跳过）
```
- 关键演进：此前 CI 与构建是两条 workflow（CI 失败不阻断镜像）；合并后 `needs: ci` 强制串联。
- 纯文档 push 仍跑 CI 与通知，但跳过镜像构建。

### 2.2 测试执行策略
- 云端排除 4 个需要外部环境（MySQL/Redis/RabbitMQ/Langfuse）的 @SpringBootTest 集成测试：
  `!com.hmdp.TokenTest, !AgentTracerIntegrationTest, !LangfuseSmokeTest, !PickNearApplicationTests`；
- 其余全部纯 Mockito 单测（surefire 配 `-XX:+EnableDynamicAgentLoading` 解决 JDK17 mock maker）；
- 纪律："**必须保持 mvn test 全绿——新增/修改主代码时同步更新测试**"写进 CI 文档。

### 2.3 镜像与 ACR 约束（踩坑记录）
- tag 策略：`latest` + `sha-xxxxxxx`（short SHA 前 7 位可追溯）；手动触发可指定额外 tag（校验合法 Docker tag 并去重）。
- GHA 层缓存 `type=gha,scope=picknear-app` 显式 scope；依赖层用镜像层缓存（pom 不变即命中），不用 `--mount=type=cache`。
- **必须关闭 provenance/sbom**：ACR 个人版不支持 OCI attestation（报 `denied: unknown manifest class`）；
- **禁用 type=registry 构建缓存兜底**：ACR 拒绝 buildkit cacheconfig manifest。
- 无 CD 自动部署：任意机器 `docker compose pull && up -d --no-build`（镜像只在 CI 构建，禁止本地 build）。

## 3. 测试金字塔布局（128 个测试文件）

- **Agent 域占绝对多数（~100 个）**，覆盖每个子域：guard 4 策略+Manager+Callback；honesty 分类器/Hook/Gate/数值断言；
  history 压缩/fidelity/ledger/pressure；plan Parser/Validator/Tree/Legacy Router；prompt repo 四级降级；
  observability 序列化/过滤/backend；permission 校验器工厂；runtime legacy/graph 双轨；access 层。
- 业务域：auth 双策略+密码服务；CacheManager/LockTemplate；Blog 发布/查询；VoucherOrderConsumer/SeckillOrderService。
- **特色实践 1：测试目录内放文档**——`src/test/java/com/hmdp/agent/docs/00-概览与约定.md ~ 14-手动测试指南.md`
  15 篇测试域文档与测试代码同仓库同目录，讲"这个域怎么测、哪些路径靠手动"。
- **特色实践 2：一致性守护测试**——`WriteGuardConsistencyCheckTest`（config 包）：守护"写操作工具是否都注册了 Guard"
  这类跨模块不变量，防止新工具漏包守卫。
- **特色实践 3：配置属性测试**——ConfigPropertiesTest/DagPropertiesTest/EvaluationPropertiesTest 把 yaml 绑定本身纳入测试。

## 4. Runbook 文化（ops 与 agent/runbook）

- agent/runbook 3 篇（观测排障/评测排障/快速开始与配置）与设计文档分离：设计文档写"是什么"，runbook 写"坏了怎么查"。
- 日志指南 TL;DR 风格：开头直给"中间日志看不到=级别被过滤，不是没打印"、"改环境变量必须 `docker compose up -d`（recreate），restart 不生效"。
- 日志开关 6 个环境变量分层（LOG_LEVEL_HMDP/AGENT/AGENT_GUARD/AGENT_TOOL/PROMPTHOOK/INTERCEPTOR），默认 INFO，一次性或写 .env 持久开启。
- ⚠️诚实备注：包名对应以代码 grep 为准（`com.hmdp.agent` 下只有 guard/hook 有 debug 日志）——文档自我修正。

## 5. 诚实债务清单（PickNear 明文承认的技术债，NearSlot 评审对照）

| 债务 | 出处 | NearSlot 动作 |
|---|---|---|
| RabbitMQ 备用交换器未绑定监控队列，不可路由消息直接丢弃 | arch/系统架构设计.md | 消息审计必须先绑监控队列（NearSlot 用 RabbitMQ 死信队列体系解决：备用交换机/不可路由消息显式绑定监控队列，ADR-014） |
| VerifyCodeService.sendCode() 无条件回显验证码（仅内网可接受） | auth/认证与登录设计.md | 环境分支：dev 回显，prod 禁止 + CI 断言 |
| 数据权限无目标资源 ID 直接放行（自限查询用途） | agent 安全设计 | 新增写操作必须确认参数带目标 ID（评审检查项） |
| [DONE] 非终止协议（旧前端兼容残留） | SSE 规范 | NearSlot 事件协议单一事实源，弃用 [DONE] |
| 并发刷新自愈 ≠ 完整 RT Reuse Detection | 请求头规范 | NearSlot 记录 RT 已用集合，检测 reuse 立即吊销版本 |
| 评测未完成：执行质量 evaluator/Dataset 回归/分维度 judge/成本统计 | 评测设计 | NearSlot P1 路线图继承这四项 |

## 6. NearSlot 工程生命周期落地（增量建议）

1. **文档治理**：继承 check_docs.py 六检查；增加第 7 项——"quality/ 五维评审文档引用的检查单 ID 必须存在"。
2. **CI 串联**：changes→ci→build-image→notify 拓扑直接继承；NearSlot 增加第 5 job `contract`（api/design 与 OpenAPI diff 检查）。
3. **测试布局**：测试目录内放 docs（00-概览+手动指南模式）；每模块一个 WriteGuardConsistencyCheck 式不变量守护测试。
4. **runbook 独立目录**：docs/runbooks/ 与 design 文档分离；每篇开头 TL;DR。
5. **诚实债务台账**：quality/tech-debts.md 按本报告 §5 格式登记，评审时逐条对照是否复发。
