---
title: 后端工程深度研究：日志错误体系 / 版本同步 / 测试体系 / 模块化架构 / 杜绝漂移
status: current
source_of_truth:
  - ../../docs/research/10-backend-engineering-deep.md
updated: 2026-11-16
---

# 研究报告 10：后端工程深度研究（日志错误 · 版本同步 · 测试 · 模块化 · 防漂移）

> **定位**：覆盖矩阵（[00-coverage-matrix.md](00-coverage-matrix.md) §1 #9/#10/#11/#18/#19/#20 与 §2）指定的五个缺口/深化维度。前九篇回答"抄什么、领域怎么设计"，本篇回答"工程底座怎么落地、怎么保证不腐烂"。
> **证据等级**：所有 file:line 均为本工作区实读（相对 D:/Projects/TraeProjects/Studying）；NearSlot 侧因处于**开发前研究期、源码尚未实现**（[NearSlot/README.md](../README.md) "当前阶段"），"现状"列为**文档契约现状**而非运行时现状。性能数字为源项目自述，未复测。
> **纪律**：本文不修改任何其他文件；新文档入 docs/README.md 导航与覆盖矩阵登记属于 Phase 1 待办（见 §6）。

## 0. 总览（表格优先）

| 维度 | NearSlot 文档现状 | 源项目最强证据 | 结论 | 漂移风险 |
|---|---|---|---|---|
| ① 日志与错误体系 | 🟡 错误码表齐、SLO 告警齐，缺统一日志规范/traceId 传播/结构化 | PickNear 六档 env + AsyncAppender；hmdp-plus `BaseCode` 枚举 + Lua JSON 码 | 抄 PickNear 分级开关 + hmdp-plus 单一枚举，**补 MDC/traceId 与结构化的空缺** | **高**：PickNear 自身出现 0/1/2 与 200/501 双口径（research/09 F.1） |
| ② 版本号同步管理 | 🔴 仅散见 ADR-002/012、git 规范一句 | hmdp-plus `revision`+flatten（pom.xml:24,44,137-159） | 以 revision 为唯一 SSOT，四端版本由脚本校验 | **高**：hmdp-plus 前端 `0.0.0` vs 后端 `0.0.1-SNAPSHOT`，无握手 |
| ③ 测试体系 | ✅ 金字塔/必测清单已成型 | PickNear 106 个 Java 测试文件 + CI 排除 4 类；hmdp-plus 仅 3 个 | 继承 NearSlot 现有策略，**补契约测试/mutation/压测可复现** | 中：测试数量在 01/06 文档中为 102/128，实测 106（见 §3.2） |
| ④ 模块化生产级架构 | ✅ 模块树+依赖铁律已定 | hmdp-plus 8 模块 revision 统一；PickNear 单模块包内自治 | 保持 9 模块设计，补**依赖规则的可执行校验**与 ACL | 中："模块间禁依赖"目前只是文字约定 |
| ⑤ 杜绝漂移 | 🔴 仅 check_docs.py 待实现 | PickNear check_docs.py 六检查 + CI 门禁（ci-cd.yml:67-68） | 脚本化 4 类漂移检查 + CI 强制，单 SSOT | **高**：源项目自身多处文档与代码不一致（见 §5.2） |

---

## 1. 日志与错误体系

### 1.1 NearSlot 现状（文档契约）

| 项 | 现状 | 出处 | 缺口 |
|---|---|---|---|
| 日志级别 | 仅一句"logback-spring.xml，级别按包六档可调（LOG_LEVEL_MODULE 默认 INFO）" | [deployment/guide.md:56](../deployment/guide.md) | 六档具体包映射、DEBUG 内容、生产默认级别未定义 |
| 统一响应体 | `{code,message,data,timestamp}`，message 面向用户、排查信息放 traceId | [api/design.md:18-25](../api/design.md) | traceId 从何生成、放响应体还是响应头未定 |
| 错误码 | 401xx/403xx/404xx/429xx/500xx/501xx 分段；50101-50105 业务码 | [api/design.md:28-38](../api/design.md) | 无"码→枚举→异常→日志"单一映射的落地规则 |
| 可观测告警 | Prometheus counter + 5 条阈值（对账 ABNORMAL>0、Lua>200ms 等） | [quality-attributes.md:34-38](../quality/quality-attributes.md) | 日志与 trace 的关联字段（traceId/spanId）未定义 |
| 评审检查 | 五维含"可观测：日志级别/指标埋点/trace 白名单" | [review-system.md:28](../quality/review-system.md) | 无日志反模式清单，评审无抓手 |
| 编码纪律 | "catch 后必须 log.warn 带 context 或重抛"；"Lua 返回 code 必须有枚举映射" | [standards/coding.md:20-22](../standards/coding.md) | 仅限文字，无自动检查 |

覆盖矩阵明确标注 #9 为 🟡"缺统一日志规范/traceId 传播/分级"（[00-coverage-matrix.md:26](00-coverage-matrix.md)）。

### 1.2 源项目证据

| 实践 | PickNear 证据 | hmdp-plus 证据 | 对 NearSlot 的取舍 |
|---|---|---|---|
| 分级日志开关 | `md/ops/日志查看与配置指南.md:41-54`：6 个 `LOG_LEVEL_*` env（HMDP/AGENT/AGENT_GUARD/AGENT_TOOL/PROMPTHOOK/INTERCEPTOR），默认 INFO；:56-68 一次性/持久开启；:76-81 "中间日志看不到=级别被过滤" | `application.yml:57-62`：`logging.level.org.javaup: info` + `AUDIT: INFO` + 时间格式 | 继承 6 档改为 `LOG_LEVEL_{COMMON,AUTH,APPOINTMENT,ORDER,AGENT,INTERCEPTOR}`，写进 deployment/guide |
| 日志落盘 | `logback-spring.xml:3,16,18`：RollingFileAppender 30 天；:25-27 AsyncAppender queueSize 8192 / neverBlock=true；:32-34 root INFO 双 appender；:38-41 三方库降噪 WARN | `application.yml:52-56` MyBatis `StdOutImpl`（生产应关） | 继承 AsyncAppender + 降噪；**禁止 StdOutImpl 进生产** |
| 日志级别守护测试 | `src/test/java/com/hmdp/LogLevelTest.java`（98 行）直接查 Logback LoggerContext，验证 guard DEBUG 生效 | 无 | 继承：NearSlot 必须有 `LogLevelTest` 防"改配置文件不生效" |
| 观测门面与 Fail-Open | `agent/observability/api/AgentTracer.java:32-35` Fail-Open + 跨线程 `resume(ctx)`；:90-91,109-110 traceEnabled 总开关与异常降级 Noop；research/09:38 `StreamingChatInvoker.java:103` `contextWrite` 保 traceId；research/09:40 `ObservabilityTraceFilter.java:13-17,39-62` 白名单防配额耗尽（@Scheduled 2s 任务实测 21.6 万 units/月） | `application.yml:73-81` 仅 prometheus/health/info 指标 | 继承门面 + 白名单 + Fail-Open；业务 span 只放行 `nearslot.` 前缀（ADR-011） |
| 错误码单一枚举 | **反面教材** research/09 F.1：`MqSeckill.lua:13-50` 返回 200/502/511/501，但 `SeckillStockService.java:21-24,39-44` 自定义 0/1/2（自带不一致注释），`SeckillOrderService.java:52-56` 用 `r!=0` 判成功、:87 用 `SUCCESS(200)` 判成功 —— 两条调用路径口径矛盾 | `BaseCode.java:10-47` 全量枚举 10001-10013/20000-20001；:63-79 `getMsg/getRc` 反查；`HmdpFrameException.java:29-39` 构造器吃 BaseCode；`seckillVoucher.lua:16-37` 直接返回 10002/10003/10004/10005/10006/10011/10012（与枚举对齐） | **采用 hmdp-plus 模式**，明确禁止 PickNear 式双口径 |
| 审计日志与业务同事务 | research/09:49 `MqSeckill.lua:11-50`（DECR+SADD+HMSET+EXPIRE+TIME 时钟） | `seckillVoucher.lua:38-60`：扣减与 trace（logType/ts/orderId/traceId/beforeQty/changeQty/afterQty）cjson 后 `hset traceLogKey traceId logEntry`，TTL=距结束+1d | 两者都继承；NearSlot 的 `slot:trace:{slotId}` Hash field=traceId（[schema.md:44-46](../database/schema.md)） |
| 慢调用/上下文告警 | research/09:51 `RefreshTokenInterceptor.java:128-131` >100ms 告警；:51 `LoginInterceptor.java:17-34,39-43` 掩码日志 | `application.yml:61-62` dateformat mm:ss.SSS | 继承：拦截器 >100ms 告警（对齐 SLO 已列阈值） |
| 恒定反模式 | research/06:80-83 日志指南诚实备注"包名对应以代码 grep 为准"；测试账号硬编码（research/05:65）、token 存 localStorage + console.log（research/05:91-93） | MyBatis StdOutImpl 打屏 | 列为禁止清单 |

### 1.3 建议与验收标准

| # | 建议 | 验收标准（可自动化） |
|---|---|---|
| L1 | 建立 `docs/standards/logging.md`（或扩 deployment/guide §6）：级别定义表（ERROR=需人工介入/WARN=可自愈异常/INFO=关键状态迁移/DEBUG=判定过程）、六档 env→包映射、生产默认 INFO | check_docs 能解析级别表；`LogLevelTest` 断言六档 logger effective level 与文档一致 |
| L2 | 统一结构化日志：pattern 含 `traceId=%X{traceId:-}`、`spanId`、`userId`（脱敏）；MDC 在 **Controller 入口/拦截器/MQ 消费者/Agent 异步入口** 四处 put/clear（finally） | 集成测试断言一次请求的所有日志行 traceId 非空且一致；异步线程出口 MDC 已清理（防 Tomcat 线程复用污染，参照 research/09:39） |
| L3 | traceId 来源统一：网关/拦截器生成（MDC + 响应头 `X-Trace-Id`），**orderId 与 traceId 分别取号**（ADR-007），Lua trace field=traceId | 契约测试断言响应头存在且 32/64 位；`slot:trace` 的 field 与响应头一致；禁止 `orderId==traceId` 的断言 |
| L4 | 错误码单一 SSOT：新增 `ErrorCode` 枚举（分段对齐 api/design §3）+ `BizException(ErrorCode)` + Lua 返回码只能引用枚举；异常→日志级别映射（429xx=WARN，500xx=ERROR 带 stack） | 静态检查：Lua 中 `return ... code` 的数值必须出现在枚举中（RESEARCH 脚本）；`WriteGuard` 式守护测试：所有 `@RestController` 异常出口都经统一 `@RestControllerAdvice` |
| L5 | 禁止清单进评审检查单：no `System.out`、no `console.log(token)`、no `StdOutImpl` in prod、no 硬编码凭据、no 吞异常 | grep 脚本 CI 门禁命中即失败；G3/G4 检查单勾选 |

### 1.4 漂移风险

> **高**。PickNear 实证：文档称"6 个 LOG_LEVEL_* 开关"，但 `日志查看与配置指南.md:54` 自己承认部分包"一般没有 debug 日志"——**开关存在 ≠ 埋点存在**；更严重的是 Lua 返回码双口径（research/09 F.1）导致同一操作两条路径判定相反。NearSlot 若只抄"枚举"和"日志开关"的形，不建立"码→日志→响应→测试"的闭环校验，漂移会在抢约链首次异常时以"对账误报 + 用户看不到正确错误"的形式爆发。

---

## 2. 版本号同步管理

### 2.1 NearSlot 现状（文档契约）

| 版本维度 | NearSlot 现状 | 出处 | 缺口 |
|---|---|---|---|
| 构建版本 | 明确采用 Maven revision + flatten-maven-plugin（hmdp-plus 模式）；发布打 v{semver} tag；CHANGELOG 记 breaking | [standards/git.md:31-32](../standards/git.md)；[research/03:20](03-framework-frontend.md) | 无 `pom.xml` 落地（源码未开始），无 revision 值定义 |
| 前后端握手 | 无。仅约定鉴权头/响应头协议（X-Token-Refresh 等） | [api/design.md:41-45](../api/design.md) | 无客户端版本头、无兼容矩阵、无强制升级策略 |
| API 版本 | 版本进路径 `/api/v1/**`；破坏性变更升 `/api/v2` + CHANGELOG；springdoc 生成 openapi.json 为唯一事实源 | [api/design.md:6,67-70](../api/design.md) | openapi.json 未入库路径/schema 漂移无检测 |
| DB 迁移版本 | SQL 编号脚本 + `CREATE TABLE IF NOT EXISTS` + 破坏性列双写窗口；schema.md 与 entity 同步更新 | [schema.md:49-52](../database/schema.md) | 无版本号表/校验和、无与代码版本绑定 |
| 配置版本 | 配置分层 application.yaml→profile→env→启动参数，prod 不落镜像 | [deployment/guide.md:53-56](../deployment/guide.md) | 无 config version 标识/灰度校验 |
| Token 版本 | JWT claim `version` + Redis valid/current 双版本键，登录/改密 bump、刷新不 bump | [ADR-002](../architecture/decisions.md)（[research/01:43](01-picknear-research.md)） | 与"构建版本"是两套语义，文档需显式区分防混淆 |

覆盖矩阵 #10 标 🔴"缺跨端版本同步专章"（[00-coverage-matrix.md:27](00-coverage-matrix.md)），§4 待办明确"版本号同步脚本：pom revision / frontend package.json / API 版本头 / DB migration 版本四者一致性校验"（[00-coverage-matrix.md:62](00-coverage-matrix.md)）。

### 2.2 源项目证据

| 机制 | PickNear | hmdp-plus | 结论 |
|---|---|---|---|
| revision + flatten | 单模块，直接继承 `spring-boot-starter-parent:3.4.4`（`pom.xml:7-12`），java.version 17（:22） | 根 `pom.xml:24` `${revision}`、:44 `${revision`、:32-41 八模块、:57 flatten 版本属性、:135-159 `flatten-maven-plugin`（updatePomFile=true，process-resources flatten / clean 清理）；**全部 21 个子 pom 的 parent/自身版本均为 `${revision}`** | 直接继承 hmdp-plus 方案；NearSlot 9 模块全用 `${revision}`，禁子模块硬编码版本 |
| 前端版本 | 独立仓库（AGENTS.md 目录说明），无版本同步机制 | `hmdp-vue3/package.json:3` `"version": "0.0.0"`（占位，与后端 `0.0.1-SNAPSHOT` 无关） | **两项目均无前后端握手**——NearSlot 属原创设计，不可"抄"，需自建 |
| API 版本 | 未在源码中找到显式 `/api/v1` 路径版本（前端 baseURL `/api`，research/05:14） | knife4j 4.3.0 + openapi 2.2.0（pom.xml:68-69） | NearSlot 的路径版本设计领先于两个源项目，保持 |
| DB 迁移版本 | `db/heima-init.sql`、`db/hmdp.sql`、`db/local_prompt.sql` 三个脚本，无版本表 | `sql/1_create_database.sql`、`sql/hmdp_0.sql`、`sql/hmdp_1.sql`（2 库×23 表同构，research/02 §5），无迁移版本表/checksum | 两项目都靠"脚本编号 + 人工"——NearSlot 应升级为 Flyway 式版本表 |
| 配置版本 | 日志级别 env 化（research/06:82）；`config/application-prod.yaml` 挂载于 2026-08-05 移除（日志指南:39） | `application.yml` 无 config.version 字段（grep 无命中） | 无现成可抄；NearSlot 自建 `config.version` + 启动日志打印 |

### 2.3 建议与验收标准

| # | 建议 | 验收标准 |
|---|---|---|
| V1 | 根 `pom.xml` 设 `${revision>x.y.z-SNAPSHOT` + flatten 1.4.1（照搬 hmdp-plus pom.xml:135-159）；所有 9 模块 pom 只用 `${revision}` | `mvn help:evaluate -Dexpression=project.version -q -DforceStdout` 在 10 个 pom 上输出一致；CI 脚本 `check_versions.py` 断言无硬编码 `${version` 数字 |
| V2 | 建 `version.properties`（或 `VERSION` 文件）作为**唯一 SSOT**，含 `revision / apiMajor / frontendVersion / dbSchemaVersion / configVersion` 五字段；Maven 通过 properties 读入，前端构建通过 `vite define` 注入 | 修改一处即全链路生效；`check_versions.py` 比对 pom revision、frontend package.json、openapi.json info.version、`db/schema_migrations` 最新版本号、配置 `nearslot.config.version`，任一不等退出 1 |
| V3 | 前后端握手：请求头 `X-Client-Version`、`X-Api-Version: v1`；响应头 `X-Server-Version`、`X-Min-Client-Version`；不兼容返回 `426 Upgrade Required` + 错误码 `49901` | 契约测试覆盖三场景（兼容/落后/超前）；前端拦截器收到 426 弹出升级提示而非重试（参照 research/05:26-31 统一错误出口） |
| V4 | DB 迁移版本化：`db/schema_migrations(version, checksum, applied_at)`；Flyway 或自研脚本按序执行，校验和防手工改脚本；版本号写入 REST `/actuator/info` | 起空库跑 `migrate` 后版本表 == version.properties.dbSchemaVersion；篡改历史脚本 checksum 校验失败；CI integration job 验证 |
| V5 | 配置版本：每个 profile 的 yaml 顶层 `nearslot.config.version`，启动时 INFO 打印；配置变更必须升版本并进 CHANGELOG | 启动日志可 grep 到版本；跨版本灰度时两套配置并存不串（compose env 覆盖测试） |
| V6 | CHANGELOG + tag `v{semver}` 由 CI 校验：tag 与 revision 一致才允许 build-image | `check_versions.py --tag` 在 CI build-image 前置执行（ci-cd.yml:97-104 模式） |

### 2.4 漂移风险

> **高**。最典型的漂移是**"一个版本变成五个版本"**：hmdp-plus 已实证后端 `0.0.1-SNAPSHOT` 与前端 `0.0.0` 长期不一致且无人察觉（pom.xml:44 vs package.json:3）；NearSlot 叠加 API v1、DB schema、config 后，若不建 SSOT + CI 校验，会在"前端缓存旧包调用新 API"时表现为 404/字段缺失，而源码里每个文件单独看都是对的。**Token version 与构建 version 必须文档显式区分**（一个是用户会话凭证代次，一个是产物代次）。

---

## 3. 测试体系

### 3.1 NearSlot 现状（已较完整）

| 层 | 占比/门禁 | 工具/对象 | 出处 |
|---|---|---|---|
| 单元 70% | 策略/Hook/规划器/工具 | JUnit5+Mockito+AssertJ | [testing/strategy.md:9-13](../testing/strategy.md) |
| 集成 20% | Controller+Redis/MySQL/RabbitMQ | Testcontainers + `@SpringBootTest`，`@Tag("integration")` CI 默认排除 | [testing/strategy.md:14-15](../testing/strategy.md)；[deployment/guide.md:44](../deployment/guide.md) |
| E2E/压测 10% | 主链路+性能基线 | Playwright + wrk | [testing/strategy.md:13](../testing/strategy.md) |
| 覆盖率 | 行覆盖 ≥80%，核心包 ≥85%，JaCoCo 入 CI | — | [testing/strategy.md:16](../testing/strategy.md)；[quality-attributes.md:23](../quality/quality-attributes.md) |
| 必测清单 | Lua 语义（回补反转 ADR-005）、框架模块独立单测、Guard/Hook/Plan 策略、Token 全生命周期、MQ 消费模板 | — | [testing/strategy.md:19-25](../testing/strategy.md) |
| 环境 | Testcontainers 统一 mysql/redis/rabbitmq，SQL 与生产同构；禁共享可变静态；Clock 注入；JsonAssert 宽松 | — | [testing/strategy.md:28-31](../testing/strategy.md) |
| 验收基线 | 空接口 TPS ≥5000、抢约 P99 ≤200ms、Token 失效 100%、零越权 | — | [testing/strategy.md:34-38](../testing/strategy.md) |

### 3.2 源项目证据

| 维度 | PickNear | hmdp-plus | 结论 |
|---|---|---|---|
| 测试规模 | 实读 `PickNear/picknear/src/test/**/*.java` = **106 个文件**；research/01:117 写"102 个"、research/06:66 写"128 个测试文件" | research/02:107 明确"测试仅 3 个（ApplicationTests/NormalTest/RedissonTest），框架模块零测试、秒杀闭环零测试" | NearSlot 策略已领先两者；但**源文档自身数字不一致**，引用须以实时 glob 为准 |
| 高密度域 | research/06:67-71：Agent 域 ~100 个（guard 4 策略+Manager+Callback、honesty、history 压缩/fidelity/ledger、plan Router、prompt 四级降级、observability、permission）；业务域 auth/CacheManager/LockTemplate/Blog/VoucherOrderConsumer | 无 | 继承"策略级穷举"密度到 NearSlot 的 appointment/agent 域 |
| 特色守护测试 | research/06:74 `WriteGuardConsistencyCheckTest`（所有写工具是否都注册 Guard）；:76 ConfigPropertiesTest/DagPropertiesTest/EvaluationPropertiesTest（yaml 绑定纳入测试）；测试目录内放 15 篇文档（:72-73） | 无 | 三项全继承：NearSlot 每模块一个"不变量守护测试"；测试目录内放测试域文档 |
| CI 门禁 | `ci-cd.yml:67-68` check_docs.py；:70-79 JDK17+compile；:81-91 mvn test 排除 4 个集成类（!TokenTest/!AgentTracerIntegrationTest/!LangfuseSmokeTest/!PickNearApplicationTests），`-DfailIfNoTests=false`；:97-104 build-image `needs:[ci,changes]` | 无 CI 文件（research/02:108） | 继承串联拓扑；NearSlot 用 `@Tag("integration")` 替代 !类名（已在 strategy 明确），并新增 contract/version/mutation job |
| Lua 测试 | research/06:54 4 个集成类被排除；research/09 F.1 暴露 Lua 返回码与 Java 判定矛盾——**说明缺少 Lua 契约测试** | `seckillVoucher.lua:16-37` 返回码与 BaseCode 对齐，但 hmdp-plus 测试仅 3 个，Lua 零测试 | NearSlot 必测清单已列 Lua 语义（strategy:21）；补"Lua 返回码 ↔ 枚举"契约测试是最高 ROI |
| 压测方法 | research/01:117-121 自述 wrk 峰值 16000+、空接口 2800→5500；research/06:87 "压测条件未记录"为诚实债务 | 无 | NearSlot 压测报告必须带条件（机器/参数/数据集/版本），否则数字不可信 |

### 3.3 建议与验收标准（在现有 strategy 之上补齐）

| # | 建议 | 验收标准 |
|---|---|---|
| T1 | 契约测试 job：springdoc 生成 openapi.json 入库（`api/openapi.json`），CI diff 检测破坏性变更（删字段/改类型/删路径），破坏性变更必须伴随 `/api/v{N+1}` | `contract` job 在 PR 上跑；人工批准的变更写入 `api/changelog.md`，CI 校验 diff 与 changelog 一致 |
| T2 | Lua 语义测试用 Testcontainers-Redis 真实执行：断言每个返回码 ↔ `ErrorCode` 枚举一一对应；回补反转（beforeQty/afterQty 方向）、一人一时段 Set、技师时间轴重叠、trace 字段齐全 | 枚举新增码而 Lua 未覆盖 → 守护测试失败；与研究/09 F.1 同类缺陷能被自动拦截 |
| T3 | mutation testing（PIT / jester 类）用于核心包 auth/appointment/order，阈值 ≥60% mutation score（覆盖率≠有效性） | CI 每周定时跑（非每次 PR，控制时长），报告入库；连续两次低于阈值触发 G4 门禁 |
| T4 | 每模块不变量守护测试：framework 零业务 import、module 间零互相依赖、所有 `@RestController` 异常走 Advice、所有写工具/预约入口过闸门（GEO/等级/时间） | ArchUnit 规则即代码；`mvn test` 全绿才放行（ci-cd.yml:81-91 模式） |
| T5 | 压测可复现：脚本 + 参数 + 数据集 + 版本 + 机器规格入 `performance/reports/YYYYMMDD-*.md`；SLO 断言（P99≤200ms、零超卖）自动化 | 报告字段缺一即 check_docs 失败；数字与 quality-attributes 表一致 |
| T6 | 测试目录内放文档（10-xx 测试域约定 + 手动测试指南），守护"哪些路径靠手动" | 文档与测试同 PR；check_docs 检查链接可达 |

### 3.4 漂移风险

> **中**。最大的漂移不是覆盖率下降，而是**测试与实现一起错**：研究/09 F.1 中 Java 把 Lua 的 200 误判成"不能重复下单"，若测试只断言"返回值非 0 即失败"则永远绿灯。解法是契约/守护测试断言**契约本身**（枚举存在性、跨模块依赖方向），而非只断言实现输出。另需把 01/06/09 中互相矛盾的测试数量以"实时 glob"为准，文档只写"约 N 个（以 CI 报告为准）"。

---

## 4. 模块化生产级架构 & 功能域规划

### 4.1 NearSlot 现状（设计已就绪）

| 项 | 设计 | 出处 |
|---|---|---|
| 模块树 | 9 模块：common（零依赖）、framework（cache/lock/idempotent/ratelimit/bloom/delayqueue/mq/id）、6 个 module（auth/shop/appointment/order/agent）、app（唯一可执行 jar） | [architecture/overview.md:40-58](../architecture/overview.md) |
| 依赖铁律 | module 间禁互相依赖（走 common 接口或 RabbitMQ 事件）；framework 禁 import 任何 module；app 唯一依赖全部 | [architecture/overview.md:60](../architecture/overview.md) |
| 功能域 | FR-1~5（认证/店铺/预约/订单履约/AI 助手）；特色域：槽位内核/Hold/候补/时空约束 | [functional-requirements.md](../quality/functional-requirements.md)；[research/08](08-nearslot-domain-research.md) |
| 跨模块协作 | 事件：`slot.opened`（取消→异步候补提升）、缓存失效广播、对账日志 | [research/08:5-6](08-nearslot-domain-research.md)；[research/02:68](02-hmdp-plus-research.md) |
| 版本管理 | revision + flatten（见 §2） | research/03:20 |

覆盖矩阵 #18/#19 均为 ✅（[00-coverage-matrix.md:34-35](00-coverage-matrix.md)）——**设计层面已充分，缺的是可执行护栏与 ACL 细节**。

### 4.2 源项目证据

| 主题 | PickNear（单模块逻辑微服务） | hmdp-plus（8 模块物理隔离） | NearSlot 取舍 |
|---|---|---|---|
| 边界形态 | research/01 §2：单 Maven 模块内 `auth/`/`voucher/`/`agent/` 子包自包含（controller/dto/entity/service），@RestController 16/@Service 18/@Configuration 33 | 根 pom.xml:32-41 八模块；framework 五件套（lock 19 类/idempotent 7 类/ratelimit 16 类/bloom 5 类/delayqueue 12 类）全部注解+AOP+自动装配，业务零侵入（research/02 §2） | 保持 overview 的 9 模块；framework 照 hmdp-plus 做成注解化，业务只写注解 |
| 依赖规则 | 包间自由引用、易腐化（research/03 §1 表） | 业务→framework 单向，清晰（同表） | 继承 hmdp-plus 单向依赖，但**用 ArchUnit 固化**（源项目都只靠人自觉） |
| 防腐层 ACL | 无显式 ACL；发 MQ 在 Service 内 `TransactionSynchronization.afterCommit`（research/09:60 `VoucherOrderProducer.java:36-47`） | MQ 模板 `AbstractProducerHandler/AbstractConsumerHandler` 统一封装（research/07 §5.3）；`MessageExtend` 作为传输模型与业务 entity 隔离 | NearSlot 在 framework/mq 提供 `Event/Message` 翻译层：module 只发领域事件，框架转为 RabbitMQ DTO；禁止 Spring AMQP 包出现在 module service 签名 |
| DDD-ish 规划 | Agent 域内部分层（api/core/support/backend）有明确依赖方向（AgentTracer.java:21-26） | 常量/枚举/异常在 hmdp-common（BaseCode/HmdpFrameException），业务实体在 core-service | 把 ErrorCode/领域枚举/Result 全放 common；module 内按 api(controller)→domain(service)→infra(mapper/redis) 分层 |
| 模块测试策略 | 测试密度集中 Agent 域；守护测试 WriteGuardConsistencyCheckTest | 框架零测试（research/02:107） | 每模块 own 测试；framework 必须有独立单测（strategy:22 已列），ArchUnit 校验分层 |
| 构建速度 | 快（单模块） | 慢（8 模块，但 revision 统一） | research/03 §1 已权衡"4~5 模块为宜"，overview 定 9 模块；用 `-pl -am` 增量构建缓解 |

### 4.3 建议与验收标准

| # | 建议 | 验收标准 |
|---|---|---|
| A1 | ArchUnit 规则写进 `nearslot-common` 测试：framework 不依赖任何 module；module 之间零依赖；common 零 Spring 业务依赖；app 只做装配 | `mvn test` 失败即阻断（T4 共用门禁） |
| A2 | 定义 ACL：`nearslot-framework/mq` 暴露 `DomainEventPublisher.publish(event)`，内部映射为 RabbitMQ `Message` 等价 DTO；module 不 import `org.springframework.amqp.*` | ArchUnit 断言 module 包无 amqp 依赖；事件类与 MQ DTO 分离，字段变更互不影响（契约测试覆盖） |
| A3 | 功能域到包映射表入 docs/architecture/overview.md：FR-x → module → 关键类 → 数据表/Redis 键 → 事件 | 评审 G2 逐项核对；新增 FR 必须同步更新表（check_docs 检查锚点存在） |
| A4 | DDD 分层：module 内 `api / domain / infra` 三包；domain 不依赖 Spring Web；跨 module 只用 common 的领域接口或事件 | ArchUnit 规则；Code Review checklist 勾选 |
| A5 | framework 五件套的注解化验收：每个能力一个注解 + 一个 Aspect + 一个自动装配，业务零样板代码 | 与 hmdp-plus 对照表逐项过；每个框架件有独立单测 + 一个业务侧集成示例 |

### 4.4 漂移风险

> **中**。"模块间禁止互相依赖"目前只是 overview 里的一句话，源项目（PickNear 包间自由引用）已证明**没有机器检查的依赖规则会在 3~5 个 PR 内腐化**。另一风险是 ACL 缺失导致 MQ/Redis 细节渗透进 module service，届时"换消息中间件"或"分片规则变更"会变成全量改动。用 ArchUnit 把规则变成代码是唯一可持续解法。

---

## 5. 杜绝漂移（code-doc / API / schema / version）

### 5.1 NearSlot 现状

| 漂移类型 | 现有防线 | 出处 | 状态 |
|---|---|---|---|
| code-doc | frontmatter（status/source_of_truth/superseded_by）+ check_docs.py 待实现；默认不新建文档、历史靠 Git | [docs/README.md 治理规则](../README.md)；[standards/doc.md](../standards/doc.md) | 🔴 脚本未实现（README 已知待办 1；覆盖矩阵 §4 待办） |
| API | springdoc 生成 openapi.json 为唯一事实源；api/design.md 只写规范；破坏性变更升 v2 + CHANGELOG | [api/design.md:67-70](../api/design.md) | 🟡 有规范无 diff 检查 |
| schema | schema.md 与 entity 同步；SQL 编号脚本/双写窗口；PR 必含 SQL 变更说明 | [schema.md:49-52](../database/schema.md) | 🟡 有约定无校验 |
| version | revision+flatten、CHANGELOG、tag 规范 | [standards/git.md:29-32](../standards/git.md) | 🔴 无四端一致性脚本 |
| 评审反哺 | 门禁发现通用问题→更新 standards/或新增 ADR→check_docs 校验 | [review-system.md:36-38](../quality/review-system.md) | 🟡 依赖脚本落地 |

### 5.2 源项目证据：漂移真实发生过的证据

| 漂移实例 | 证据 | 教训 |
|---|---|---|
| Lua 返回码双口径 | research/09 F.1：`SeckillStockService.java:21-24,39-44` 0/1/2 自定义且自带"与 SeckillOrderCode 不一致"注释；`SeckillOrderService.java:52-56` 用 `r!=0`、:87 用 200 判成功 | 文档/注释自认漂移但未修复 = 漂移已失控；必须单一枚举 + 契约测试（§1 L4 / §3 T2） |
| 注释陈旧 | research/09 F.5：`RefreshExpiredToken.lua:8` 头注释 `KEYS[3]=token:version:`，实际传 `LOGIN_VALID_VERSION_KEY`（TokenService.java:181） | Lua 注释必须与 KEYS/ARGV 一同纳入检查 |
| 文档数字矛盾 | research/01:117 "102 测试" vs research/06:66 "128 测试文件" vs 实读 106 | 量化声明必须附"以 CI 报告为准"或实时生成 |
| 日志开关与埋点错位 | 日志指南:54 自承"只有 guard 和 hook 两个包有 debug 日志"，6 档开关中 AGENT_TOOL 无 debug | "配置项存在"不等于"实现存在"；需守护测试 |
| 文档自我修正文化 | research/06:83 诚实备注"包名对应以代码 grep 为准" | **继承诚实声明文化**，但把"以 grep 为准"升级为"CI 自动 grep" |
| check_docs.py 六检查 | `scripts/docs/check_docs.py:15` status 三态；:60 `is_git_tracked`；:134 状态校验；:148 source_of_truth 必须存在且被 Git 跟踪；:164 链接可达；:167-174 current 必须从模块 README 可达；:178 legacy 必须不被链接；:190 任一错误 exit 1 | 直接继承，NearSlot 增加第 7/8 项（见下） |
| CI 强制 | `ci-cd.yml:50` git diff 判定纯文档变更；:67-68 docs 检查在 compile 之前；:97-104 build-image `needs:[ci,changes]` | 继承"检查失败不构建镜像"的串联拓扑 |

### 5.3 建议与验收标准（四道确定性闸门）

| 闸门 | 脚本 | 检查项 | 验收标准 |
|---|---|---|---|
| D1 code-doc | `scripts/docs/check_docs.py`（继承 + 扩展） | 原六检查 + ⑦ frontmatter `updated` 不早于被引用源码的最后提交时间 + ⑧ 评审检查单 ID 存在 + ⑨ 本文导航登记（docs/README 与覆盖矩阵须列 10） | 任一失败 exit 1；CI docs job 强制；新文档必须先在导航表登记（否则⑥不可达检查已能拦住） |
| D2 API | `scripts/api/check_openapi.py` | openapi.json 与 api/design.md 错误码表一致；路径版本 v1/v2 与 CHANGELOG 一致；破坏性 diff 无 changelog 即失败 | contract job 在 PR 上运行；生成物入库且 git diff 可审 |
| D3 schema | `scripts/db/check_schema.py` | entity 注解/字段 ↔ schema.md 表清单 ↔ SQL 脚本三向一致；Redis 键 ↔ RedisConstants 常量一致（TTL 显式）；迁移版本号单调 | integration job 起空库执行迁移并比对版本表；键常量遗漏即失败 |
| D4 version | `scripts/check_versions.py` | §2 V2 的五字段一致；tag 与 revision 一致；openapi info.version 与 apiMajor 一致 | build-image 前置执行；不一致禁止出镜像 |
| 统一入口 | `scripts/check_all.sh` 顺序执行 D1→D4→compile→test | — | CI 一条命令；本地提交前同一个命令（消除"本地绿 CI 红"） |

**单一事实源（SSOT）声明**：
- 版本 → `version.properties`（一源五字段）
- API 明细 → `openapi.json`（由 springdoc 注解生成，design.md 只写规范）
- Schema → SQL 迁移脚本（entity 由脚本生成或对照校验，schema.md 是索引而非副本）
- 错误码 → `ErrorCode` 枚举（Lua/Java/文档/前端字典均从此派生）
- 文档路由 → `docs/README.md`（check_docs 的双向可达检查保证）

### 5.4 漂移风险

> **高，且是其余四个维度的元风险**。源项目实证：漂移不是"偶尔写错"，而是**结构性必然**——同一个事实被写在 Lua 注释、Java 常量、文档、前端字典四处，任何一处更新遗漏即产生不一致。NearSlot 的对策不是"更仔细地写文档"，而是**减少事实副本数量**（SSOT + 生成）+ **把一致性变成 CI 可失败的事件**（四道闸门）。当前最大缺口是 check_docs.py 尚未实现（README 待办 1），在此之前的任何"文档已同步"声明都不可信。

---

## 6. Phase 1 落地清单（按依赖排序，表格）

| 序 | 动作 | 依赖 | 承载文件（新/改） | 验收 |
|---|---|---|---|---|
| 1 | 新建本文并登记导航 | 无 | [00-coverage-matrix.md](00-coverage-matrix.md) §2、[docs/README.md](../README.md) 导航表（**本轮不改，Phase 1 待办**） | check_docs ⑥ current 可达通过 |
| 2 | 实现 `scripts/docs/check_docs.py` 六+三检查 | 1 | NearSlot/scripts/docs/（新建） | CI docs job 绿 |
| 3 | 建 `version.properties` + `check_versions.py` | 2 | 根 pom / frontend / openapi / db | §2 V1-V6 全过 |
| 4 | 建 ErrorCode 枚举 + 统一 Advice + Lua 码对齐 | 3 | nearslot-common + Lua 脚本 | §1 L4 + §3 T2 |
| 5 | 日志规范 + MDC/traceId 四处传递 + LogLevelTest | 4 | standards/logging.md（或 deployment/guide §6） | §1 L1-L5 |
| 6 | ArchUnit 依赖规则 + ACL + 每模块守护测试 | 2 | 各 module 测试 | §4 A1-A5 / §3 T4 |
| 7 | contract / mutation / 压测报告门禁 | 3,6 | CI workflow + api/openapi.json | §3 T1,T3,T5,T6 |
| 8 | `scripts/check_all.sh` 统一入口 + CI 串联 | 2-7 | CI workflow | 本地=CI 一条命令 |

---

## 7. 证据索引（文件:行）

| 主题 | 关键证据 |
|---|---|
| 日志配置 | `PickNear/picknear/src/main/resources/logback-spring.xml:3,9,16,18,22,25,27,32-34,38-41`；`PickNear/md/ops/日志查看与配置指南.md:14-15,41-54,56-81`；`PickNear/picknear/src/test/java/com/hmdp/LogLevelTest.java:1-98` |
| trace/观测 | `PickNear/picknear/src/main/java/com/hmdp/agent/observability/api/AgentTracer.java:21-35,90-91,109-110,122-129,139-146`；`NearSlot/docs/research/09-picknear-verification.md:38-41,51` |
| 错误码 | `hmdp-plus/hmdp-common/src/main/java/org/javaup/enums/BaseCode.java:10-47,63-79`；`hmdp-plus/hmdp-common/src/main/java/org/javaup/exception/HmdpFrameException.java:15-39`；`hmdp-plus/hmdp-core-service/src/main/resources/lua/seckillVoucher.lua:16-37,45-60`；`NearSlot/docs/research/09-picknear-verification.md:65,69` |
| 版本 | `hmdp-plus/pom.xml:24,32-41,44,57,135-159`；`hmdp-plus/hmdp-vue3/package.json:3`；`PickNear/picknear/pom.xml:7-12,19-27`；`hmdp-plus/sql/1_create_database.sql` 等 |
| 测试/CI | `PickNear/.github/workflows/ci-cd.yml:50,67-68,70-91,97-104`；`PickNear/scripts/docs/check_docs.py:15,60,117-118,134,148,164,167-174,178,190`；`NearSlot/docs/testing/strategy.md:9-38` |
| 配置 | `hmdp-plus/hmdp-core-service/src/main/resources/application.yml:22-27,52-62,73-81`；`NearSlot/docs/deployment/guide.md:28-56` |
| NearSlot 规范 | `NearSlot/docs/api/design.md:6,18-45,67-70`；`NearSlot/docs/database/schema.md:44-52`；`NearSlot/docs/architecture/overview.md:40-60`；`NearSlot/docs/standards/coding.md:20-22`；`NearSlot/docs/standards/git.md:29-32`；`NearSlot/docs/quality/quality-attributes.md:23-38`；`NearSlot/docs/quality/review-system.md:17-38`；`NearSlot/docs/research/00-coverage-matrix.md:26-28,34-37,46,62` |

> **不确定性声明**：NearSlot 无源码，本文所有"建议"均为设计输入，须在 Phase 1 以实际代码+CI 验证；两源项目的性能数字（TPS/token 削减等）为自述、未复测。外部系统设计文献见 research/08 §11。

—— 完 ——
