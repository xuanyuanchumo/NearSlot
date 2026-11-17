---
title: 架构组织与前端工程对比研究
status: current
source_of_truth:
  - ../../docs/research/03-framework-frontend.md
updated: 2026-11-16
---

# 研究报告 03：架构组织、前端工程与数据结构选型

## 1. 后端架构组织对比：单模块分包 vs 多模块 Maven

| 维度 | PickNear（单模块 com.hmdp） | hmdp-plus（8 模块 org.javaup） | NearSlot 取舍 |
|---|---|---|---|
| 模块边界 | 包级（auth/agent/voucher 自包含子包） | 物理隔离（framework 与业务分离） | **单应用 + Maven 多模块**折中 |
| 依赖方向 | 包间自由引用，易腐化 | 业务→框架单向依赖，清晰 | 强制业务→framework 单向 |
| 复用性 | utils 手工封装，复制成本高 | 注解+AOP 框架件（锁/幂等/限流/布隆/延迟队列） | 框架件独立模块沉淀 |
| 构建速度 | 快（单模块） | 慢（8 模块） | 4~5 模块为宜 |
| 适合团队 | 1~3 人小队 | 3~10 人 | 按团队规模选择 |
| 版本管理 | 单版本 | revision+flatten 统一版本 | revision+flatten |

**NearSlot 结论（模块化单体 + 适度多模块）**：

```
nearslot/
  nearslot-common        — 常量/枚举/异常/Result/ThreadLocal 工具
  nearslot-framework     — cache(三防+多级)/lock/idempotent/ratelimit/bloom/delayqueue/mq(生产+消费模板)/id-generator
  nearslot-module-auth   — 双 Token 认证（含 Lua 脚本族）
  nearslot-module-shop   — 服务商/时段/GEOSearch
  nearslot-module-appointment — 预约核心（秒杀式抢约+候补+对账闭环）
  nearslot-module-order  — 订单/支付回调/路由表
  nearslot-module-agent  — AI Agent（两阶段规划/双闸口/观测/提示词）
  nearslot-app           — 启动器（装配全部模块，唯一可执行 jar）
```

规则：module 之间禁止互相依赖（通过 common 接口或事件解耦）；app 是唯一允许依赖所有模块的聚合层；framework 模块零业务语义。

## 2. 前端工程对比

| 维度 | hmdp-vue3 | PickNear 前端（独立仓库） | NearSlot 目标 |
|---|---|---|---|
| 框架 | Vue 3.5.13 | Vue 3.5 | Vue 3.5+ |
| 语言 | JavaScript | TypeScript 6 | **TypeScript 6（strict）** |
| 构建 | Vite 6.2.4 | Vite 8 | **Vite+ 1.6（内置 vite 8 + rolldown）** |
| UI 库 | Element Plus 2.9.7（auto-import） | Element Plus | Element Plus + auto-import |
| 状态 | Pinia 3.0.1 + persistedstate 4.2 | Pinia | Pinia + persist |
| HTTP | axios 1.8.4 | axios | axios 封装 |
| 精度 | json-bigint storeAsString | — | **后端 WRITE_NUMBERS_AS_STRINGS + 前端 json-bigint 双保险** |
| 工程化 | eslint9/prettier/husky/lint-staged/unplugin | 同 | **Vite+ 统一：oxlint + oxfmt + vitest（`vp check`）+ unplugin** |
| 鉴权 | Authorization: token（无 Bearer） | Bearer AT + Refresh-Token 双通道 | **采用 PickNear 协议（小写 authorization + Refresh-Token + 401 单飞）** |

关键经验（hmdp-vue3 request.js）：401 统一跳 /login；token 存 Pinia persist（key 'Hmdp-User'）；axios transformResponse 用 json-bigint 防雪花 ID 精度丢失——**NearSlot 必须同时做后端串化和前端 json-bigint，缺一不可**。

## 3. 响应式布局与 UX/UI 标准（NearSlot 规范基线）

- 断点：xs<768（单列，底部 Tab 导航）、sm 768~992（单列+侧滑）、md 992~1200（双列）、lg≥1200（栅格 12 列+侧边栏常驻）。
- 布局：移动优先；预约主流程（列表→详情→选时段→支付→核销）在任何断点下不超过 3 次点击可达。
- 组件：表格在 xs/sm 自动转卡片列表（Element Plus 表格 + 自定义 breakpoint 渲染）；弹窗 sm 以下转全屏 Drawer。
- 可用性：所有可交互元素 ≥44×44px 触控区；表单错误即时校验+提交后汇总；加载态骨架屏（列表/详情）+按钮级 loading；空态必须给行动指引（"去逛逛"按钮）。
- 主题：CSS 变量定义色板（主色/成功/警告/危险），暗色模式通过 html[data-theme] 切换；色对比度满足 WCAG AA（4.5:1）。
- 国际化预留：文案统一走 i18n 资源文件，禁止硬编码中文串在组件内。

## 4. 数据结构与算法选型（两项目实证沉淀）

| 场景 | 数据结构 | 项目出处 | NearSlot 适配 |
|---|---|---|---|
| 会话/Token | String(JSON) + Hash | 双方 | 保持 |
| 一人一单 | Set SISMEMBER | 双方 | 一人一时段：Set `appointment:booked:{slotId}` |
| 库存预扣 | String DECR + Lua | 双方 | 时段余量 DECR |
| 排行榜/Feed | ZSet（score 时间戳/销量） | 双方 | Top 技师日榜（shopId+yyyyMMdd） |
| 签到 | BitMap BITFIELD | 双方 | 用户连续到店签到 |
| UV 统计 | HyperLogLog | hmdp-plus | 店铺页 UV |
| 附近检索 | GEO 按 typeId 分组 | 双方 | 附近服务商 GEOSEARCH |
| 防穿透 | BloomFilter（可配容量/误判率） | hmdp-plus | shop/technician 两过滤器 |
| 候补排队 | ZSet（score=时间戳）+ 余票回流 | hmdp-plus | **预约候补核心：取消→rangeByScore 取最早候补→autoIssue** |
| 延迟通知 | Redisson DelayQueue | hmdp-plus | 开抢前 2 分钟提醒 |
| 短信/推送去重 | String SETNX + TTL 窗口 | hmdp-plus | 通知控频 |
| 追踪日志 | Hash field=traceId + cjson value | hmdp-plus | 预约对账 trace |
| ID 生成 | 雪花+预生成队列（PickNear）/号段+lua 协调（hmdp-plus） | 双方 | 预生成队列版（简单高吞吐） |

算法要点：
- 库存回补必须**反转 beforeQty/afterQty** 语义（hmdp-plus rollbackRedisVoucherData），否则对账必然误报。
- Lua 返回结构化 JSON（code/beforeQty/afterQty），Java 侧 SeckillVoucherDomain 承接——错误码集中 BaseCode 枚举。
- Feed/候补扫描一律**逐页 rangeByScoreWithScoreForSortedSet + 跳过条件**，禁止一次性 ZRANGE 全量。
- 深分页用游标（lastId+limit），禁止 LIMIT 100000,20。

## 5. NearSlot 预约域核心时序（融合两项目）

```
抢预约：verifyUserLevel → Lua(余量DECR + SISMEMBER 一人一时段 + trace 日志)
      → RabbitMQ(publisher-confirm+持久化+手动 ack) → 消费端(RepeatExecuteLimit+乐观锁+路由表+对账日志)
取消：  状态机校验 → 回补 Redis(反转语义) → Top 榜回退 → 候补 ZSet rangeByScore
      → 最早期候补者 autoIssue(再次过 Lua+等级校验) → 延迟队列 2 分钟提醒
对账：  定时任务对比 Redis trace vs DB 订单 → ABNORMAL 标记 + 告警 + 回补
```

—— 完 ——
