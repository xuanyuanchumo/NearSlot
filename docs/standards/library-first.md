---
title: 成熟库优先原则（全阶段强制）
status: current
source_of_truth:
  - ../../docs/standards/library-first.md
updated: 2026-11-17
---

# 成熟库优先原则（全阶段强制）

> **一句话**：**有成熟库就用成熟库，非必要不重造轮子。**
> 适用范围：Phase 0–5 **全部阶段**、全部代码与文档，不限于某一模块。

## 0. 为什么单独立这篇

本项目此前把"框架能力注解化"写进 ADR-003/016，但**没有可执行的判定规则**，导致实际开发中反复造轮子。2026-11-17 审计发现 5 处自研替代了现成库（见 §3）。本文把原则落成"动手前的强制自检"。

## 1. 动手前必须回答的 4 个问题

写任何"工具类/框架件/算法"之前，按序自问，**答完才能写代码**：

| # | 问题 | 若答案为"有" |
|---|---|---|
| 1 | JDK / Spring 自带能力能否满足？ | 用 JDK/Spring，不写工具类 |
| 2 | 已在依赖树中的库（见 §2）能否满足？ | 直接用，不新增依赖 |
| 3 | 成熟第三方库能否满足（Redisson/Guava/MyBatis-Plus/Caffeine…）？ | 引入并用，**不手写算法** |
| 4 | 参考项目（PickNear / hmdp-plus）是否已有实现？ | 先读懂再复用或改进，**不重复发明** |

四个问题全为"否"才允许自研，且必须在 PR 描述中写明"为什么不能复用"。

## 2. 本项目已确定的能力 → 库映射（禁止替代实现）

| 能力 | **必须使用** | 禁止 |
|---|---|---|
| 分布式锁 | Redisson `RLock` / `RReadWriteLock` | 手写 SET NX + Lua 释放 |
| 限流 | Redisson `RRateLimiter` | 自写令牌桶 Lua（除非有明确性能证据） |
| 延迟队列 | Redisson `RDelayedQueue` | 自研 DelayQueue / 纯轮询扫描（轮询只可作兜底） |
| 分布式 ID | `com.baomidou.mybatisplus.core.toolkit.IdWorker` | 手写雪花 / `AtomicLong` 自增 |
| 对象映射 | Spring 容器中的 `ObjectMapper` Bean | `new ObjectMapper()` |
| 密码哈希 | `BCryptPasswordEncoder`（容器 Bean） | 自写哈希 / 明文比对 |
| 本地缓存 | Caffeine | 手写 ConcurrentHashMap + 过期 |
| 布隆过滤 | Guava `BloomFilter`（或 RedisBloom） | 手写位数组 |
| HTTP 客户端 | Spring `RestClient`/`WebClient` | `new RestTemplate()` / 手写 socket |
| JSON | Jackson | 手写拼接 |
| 定时任务 | Spring `@Scheduled` / 延迟队列 | 自建线程 sleep 循环 |
| 幂等 | Redisson `RBucket.trySet` + AOP | 手写 SETNX 切面 |
| 参数校验 | `jakarta.validation` 注解 | 手写 if 校验链 |
| 重试 | Spring Retry / Resilience4j | 手写 for + sleep |
| 分页 | MyBatis-Plus `Page` | 手写 limit 拼接 |

## 3. 2026-11-17 审计发现的违规（已修复）

| # | 自研实现 | 应使用的库 | 修复 |
|---|---|---|---|
| 1 | `AtomicLong SEQ` 生成订单号（3 处） | MP `IdWorker` | 已改 `IdGenerator.nextId()`（薄封装，可回推时间、多实例不撞号） |
| 2 | `new BCryptPasswordEncoder()` | 容器 Bean | 已改注入，新增 `PasswordConfig` |
| 3 | `new ObjectMapper()` | Spring 的 ObjectMapper | 已改构造注入 |
| 4 | 自研 `new SecureRandom()` 验证码 | 可保留（JDK 原语，非轮子） | 保留并注明理由 |
| 5 | Hold 超时纯轮询扫描 | Redisson 延迟队列 | **已修复**：主路径改 `RDelayedQueue`，60s 轮询降为幂等兜底 |
| 6 | 异常处理器另起名 `GlobalExceptionHandler` | 与参考项目一致的 `WebExceptionAdvice` | **已修复**：改名，降低接手成本 |
| 7 | 防爆破单 key（语义退化） | PickNear 双维度：计数+锁 | **已修复**：`login:fail:count:{phone}`(24h/10) + `login:lock:{phone}`(30min)，常量精确对齐 |
| 8 | 候补仅 1 个 ZSet | hmdp-plus 三键 | **已修复**：ZSet+Set+Status Hash，提升拆出 `PromotionService` |
| 9 | `ObjectMapper` 拆包后无 Bean | 显式注册（Boot 4 不再自动装配） | **已修复**：新增 `JacksonConfig`，开启 JavaTime + 忽略未知字段 |

## 3.5 实现踩坑记录（供后续复用，避免重复付费）

| 坑 | 现象 | 结论 |
|---|---|---|
| `@ConditionalOnProperty` 标在 `@RabbitListener` 方法上 | 启动即崩：`Expression parsing failed`（`#{queue.name}` 无法解析，因队列 Bean 已被移除） | 条件注解必须标在**类级**，同时控制队列声明与监听器 |
| RabbitMQ 不可用时的监听器 | 每 5s 重连刷 ERROR，掩盖真实告警、浪费 CPU | 提供 `nearslot.mq.invalidation-enabled` 开关；本地开发默认关 |
| MQ 广播失败静默吞掉 | 跨实例脏读：发布时段后其他实例仍返旧值 | `publish()` 必须返回布尔，调用方拿到 false 做**本地失效降级** |
| YAML 多个顶级 `spring:` 键 | 启动失败（配置加载期异常，无明确报错） | 同文件只能有一个 `spring:`，子配置须合并 |
| `BaseException` 的 403 段 | 权限异常误返 HTTP 200，监控无法告警 | `WebExceptionAdvice` 必须按码段完整映射 401/403/404/429 |
| 拦截器只解析 JWT 不校验 version | `bumpVersion` 形同虚设，改密后旧 AT 仍可访问（**严重安全缺陷**） | 鉴权必须**两级校验**：JWT + Redis 版本一致性；仅验签名不足以"踢出" |
| 前端代理 502 | Vite 代理指向 8081，后端跑在 48081 | 端口必须三处统一：dev/verify profile、vite proxy、nginx upstream |
| 布隆过滤器未预热 | 初始化即空集合，所有查询 mightContain=false，**全部详情 404** | 启动必须把已有数据 put 进去；布隆只答"一定不存在"，空集合=全否定 |
| CacheManager 只支持基础类型 | 缓存实体抛 "unsupported type: class ...Shop" | 实体缓存必须 JSON 序列化；`String.valueOf(entity)` 得到的是 `Shop@hash` 脏数据 |
| 旧格式缓存残留 | 改序列化方式后仍 500（读到旧 toString 数据） | 序列化格式变更须 flush 缓存或加版本前缀 |
| 时段查询需登录 | 前端浏览阶段未登录即 401 | 只读查询应放行（GET），写操作才鉴权 |
| 密码长度校验与种子数据不一致 | 改密后无法恢复原密码（`pass123` 仅 7 位，新规则要求 ≥8） | 校验规则变更时须同步更新种子数据与文档示例 |
| `updateById` 在 Mockito 中断言 | 编译报"引用不明确"（`T` 与 `Collection<T>` 双重载） | 测试里用 `ArgumentMatchers.<Entity>argThat(...)` 显式指定类型 |

## 4. 复核机制（全阶段）

| 检查点 | 动作 | 执行者 |
|---|---|---|
| G2 设计评审 | 新设计的每个"自研件"必须填 §1 四问答案 | designer |
| G3 代码评审 | grep 拦截：`new ObjectMapper|new RestTemplate|new ThreadPoolExecutor|AtomicLong.*SEQ` | reviewer |
| G4 测试评审 | 自研算法必须有对比测试（与库实现结果一致） | tester |
| 每次 PR | 新增依赖需在描述中说明"为何选它而非自研" | developer |

## 5. 允许自研的例外情形（需 ADR）

1. 成熟库**明确不满足**某些语义（如 Lua 原子脚本必须手写，因为需要单次 RTT 的复合判断）；
2. 库引入**不可接受的成本**（依赖体积 / 许可证 / 安全漏洞）；
3. 库的版本与 Spring Boot 4 **不兼容**且无替代（如本次 Redisson starter 需升 4.8.0）。

以上均须**新增 ADR 记录理由**，不得口头决定。

## 6. 与既有 ADR 的关系

- ADR-003「通用能力沉淀为 framework 模块」→ 指**封装**，不是**重写**；
- ADR-016「框架能力优先用成熟库」→ 本文是其**可执行细则**；
- 两者冲突时以本文为准。
