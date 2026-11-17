---
title: 前端 UX 深度研究：设计系统、响应式布局、用户旅程、数据结构与 SSE 流式交互
status: current
source_of_truth:
  - ../../docs/research/11-frontend-ux-deep.md
evidence_sources:
  - ../../../PickNear/md/agent/SSE后端实现规范.md
  - ../../../PickNear/md/规范/请求头设计规范.md
  - ../../../PickNear/md/ops/API接口文档.md
  - ../../../PickNear/md/agent/Agent安全与审批设计.md
  - ../../../hmdp-plus/hmdp-vue3/src/utils/request.js
  - hmdp-plus/hmdp-vue3/src/utils/format.js
  - hmdp-plus/hmdp-vue3/src/stores/modules/user.js
  - hmdp-plus/hmdp-vue3/src/views/shop/ShopDetail.vue
  - hmdp-plus/hmdp-vue3/src/views/shop/ShopList.vue
  - hmdp-plus/hmdp-vue3/src/views/index.vue
  - docs/research/03-framework-frontend.md
  - docs/research/05-frontend-ux-research.md
  - docs/research/08-nearslot-domain-research.md
  - docs/standards/frontend.md
updated: 2026-11-16
---

# 研究报告 11：前端 UX 深度研究——设计系统、响应式、旅程、数据模型与 SSE

> 定位：本文是 03 / 05 / standards/frontend.md 的纵深细化，不是重复。03 定架构选型、05 定 hmdp-vue3 全量源码复用清单、frontend.md 定规范基线；本文回答 NearSlot 前端要做成什么样、怎么验证、禁止什么。
> 证据等级：【实证】= 有源码 file:line 或明确文档引用（PickNear 文档、hmdp-vue3 源码、NearSlot 既有研究）；【推导】= 基于实证 + 08 时段预约领域的设计提案，须在 Phase 1 用测试固化。
> 阅读顺序：每维度固定五段式——现状 → 证据 → 建议 → 验收 → 反模式。所有表格先行。

---

## 0. 五维度总览与 NearSlot 现状

| # | 维度 | NearSlot 现状（截至 2026-11-16） | 证据等级 | 本文目标 |
|---|---|---|---|---|
| 1 | UX/UI 标准 | 仅有原则性规范（断点/44px/骨架/4.5:1），无 design token 表、无组件契约、无状态机规格、无动效规范 | 【实证】standards/frontend.md §3–5；03 §3 | 给出可落地的 token/组件/a11y/动效/三态规格 |
| 2 | 现代响应式布局 | 规范写死 3 断点 + Element Plus 栅格，无容器查询、无流式排版、无折叠屏策略、无 3 taps 可测定义 | 【实证】standards/frontend.md §3；03:57-58 | 补齐 mobile-first + CQ + fluid + 触控 + 3-tap 验收 |
| 3 | 用户旅程 | 只有后端时序（抢约/hold/候补），无前端旅程状态机、无 AI 助手旅程、无错误恢复旅程、无验收标准 | 【实证】03 §5；08 §4–6 | 定义端到端旅程、状态机、AC |
| 4 | 数据结构与算法 | 规范只定 json-bigint + 触底分页双保险，无前端状态模型、无 TanStack Query 缓存、无虚拟列表、无 DST 数学、无时段网格算法 | 【实证】05 §2/§3.2；standards/frontend.md §2 | 给出可直接编码的数据模型与算法约束 |
| 5 | SSE 流式交互 | 后端 SSE 契约完整（meta/plan/progress/confirm/error），前端对接要求仅 7 条文字，无重连策略、无流式可访问性、无确认续流 UI 规格 | 【实证】SSE规范 §3–8 | 定义事件状态机 + 重连 + a11y + AC |

一句话结论：NearSlot 前端规范的骨架已有（03/05/standards），缺的是可验收的深度规格——本文把每个应有翻译成 token 值、状态机、算法与 AC。时段预约（08）是与两个参考项目的根本差异，前端所有设计必须服务于槽位是带时间坐标的稀缺资源这一事实。

---

## 1. UX/UI 标准

### 1.1 现状—证据—建议—验收—反模式（总表）

| 子项 | NearSlot 现状 | 证据（file:line / 文档） | 具体建议 | 验收标准（AC） | 反模式（禁止） |
|---|---|---|---|---|---|
| 设计令牌 | 无 token 表；规范仅 CSS 变量定义色板 | 【实证】03:60；standards/frontend.md §5 | 建立 5 层 token：color/spacing/typography/radius-motion/z-index，全部 ns 前缀，语义令牌引用基础令牌 | 组件内零硬编码色值/间距；静态扫描在 vue/scss 命中裸色值数为 0（1px 边框除外） | 组件内散写 #409eff / 14px；暗色靠 important 覆盖 |
| 组件规范 | 复用 Element Plus，但无 NearSlot 组件契约 | 【实证】standards/frontend.md §1/§4；05 §5 | 定义 12 个业务组件契约（见 1.2）：Props/Emits/状态/键盘/a11y 必填 | 每组件有 Storybook story + a11y addon 0 serious 违规 | 无 props 类型约束的裸组件；用 div 加 click 当按钮 |
| 无障碍 WCAG AA | 仅对比度 ≥4.5:1、语义化、键盘可达三句 | 【实证】standards/frontend.md §5 | 落地到操作层：焦点可见、名称/角色/状态、SKU 选择键盘化、错误播报、reduced-motion | axe-core 自动扫描 0 critical/serious；键盘可完成完整预约；对比度 token 全量正文 ≥4.5:1（大字 ≥3:1） | outline:none 无替代；图标按钮无 aria-label；骨架屏无 aria-busy |
| 动效 | 无规范 | 【推导】 | token 化时长/缓动；尊重 prefers-reduced-motion；仅 transform/opacity 保 60fps | 开启系统减少动态后所有过渡时长 ≤0.01s；动效不阻塞交互 | height/top 动画引重排；加载旋转无 aria-hidden |
| 空/错/载三态 | 规范有骨架屏 + 空态引导 + 错误页 403/404 | 【实证】standards/frontend.md §4；03:59 | 统一三态组件：NsEmpty（插画+行动）、NsError（code+重试+上报）、NsSkeleton（列表/详情/时段网格三形态） | 每个异步视图三态齐备；空态必有 CTA；错误态展示后端 code 文案并可一键重试 | 空白页面无提示；错误只弹 toast 不留痕；骨架屏与真实布局跳动导致 CLS>0.1 |

### 1.2 业务组件契约清单（自建，Element Plus 之上）

| 组件 | 关键 Props | 关键 Emits | 必含状态 | a11y 要点 | 证据来源 |
|---|---|---|---|---|---|
| NsSlotGrid 时段网格 | date, slots[], timezone, selectedId | select, unselect | available/held/booked/disabled/past | 每格 role=radio + aria-checked，方向键移动，网格 role=radiogroup | 【推导】08 §2 |
| NsSlotMap 座位/技师图 | layout, occupied[], scale | seat-click | 可选/占用/过道 | SVG title；缩放按钮键盘可达；非纯颜色区分（图案+文字） | 【推导】08 §7 可变时长 |
| NsGeoPicker | coords, radiusKm, providers[] | locate, radius-change | locating/ready/denied | 定位中 aria-live；拒绝授权给手动输入兜底 | 【实证】05 §3.2 坐标硬编码反例；08 §7 |
| NsHoldTimer | expiresAt, now | expire, renew | active/warning/expired | role=timer + aria-live=polite 仅最后 60s 播报 | 【推导】08 §4 Hold 10min |
| NsBookingSummary | hold, fees, slot | confirm, edit | loading/confirming | 价格变动 aria-live | 【推导】standards/frontend.md §4 |
| NsWaitlistPosition | position, eta | join, leave | queueing/offered/expired | 位次用文本非纯色；offered 强提示 | 【实证】08 §5 位次 SSE |
| NsReviewForm | appointment, rules | submit | editing/submitting/done | 星级用 radio group + aria-label | 【推导】standards/frontend.md §4 |
| NsStateBanner | state, message, retryable | retry, dismiss | empty/error/offline | role=status 与 role=alert 区分 | 【实证】standards/frontend.md §4 |
| NsSseAnswer | stream, events[] | confirm, reject, stop | streaming/confirm/done/error | 见 §5.4 流式可访问性 | 【实证】SSE规范 §8 |
| NsAuthGuard | redirect | — | checking/authed/guest | 跳转前 aria-live 提示 | 【实证】standards/frontend.md §4 |
| NsSkeleton | variant(list/detail/grid) | — | — | aria-busy=true + aria-label=加载中 | 【实证】standards/frontend.md §3 |
| NsToastCenter | queue | close | — | role=status 非阻断；错误用 role=alert | 【实证】05 §2 统一错误出口 |

### 1.3 Design Token 基线（建议值，须经设计评审冻结）

| Token 组 | 基础令牌 | 语义令牌（亮/暗） | 对比度校验 |
|---|---|---|---|
| Color | ns-color-brand-500 取 #2f6bff；success #16a34a；warning #d97706；danger #dc2626；gray 50–900 | ns-fg, ns-bg, ns-surface, ns-border, ns-state-* | 正文 #1f2937 on #fff = 16.1:1；danger #dc2626 on #fff = 4.83:1 达标 |
| Spacing | 4px 基准刻度：1=4, 2=8, 3=12, 4=16, 6=24, 8=32 | ns-space-inset, ns-space-stack | 触控间距 ≥8px |
| Typography | 14/16/20/24/32；行高 1.5；system-ui 字体族 | ns-text-body, ns-text-label | 正文等效 ≥16px |
| Radius/Shadow | 4/8/12；shadow sm/md/lg | ns-radius-card, ns-shadow-popover | 不依赖阴影单独表达层级 |
| Motion | 100/200/300ms；ease-out cubic-bezier(.2,.8,.2,1) | ns-motion-fast/normal | reduced-motion 时归零 |
| Z-index | base=0, dropdown=1000, modal=2000, sse-overlay=2500, toast=3000 | ns-z-* | 禁止裸 9999 |

证据锚点：色板/WCAG AA 来自 03:60 与 standards/frontend.md §5；token 化是对 CSS 变量定义色板【实证】的可执行化；暗色 html[data-theme] 切换机制来自 03:60 保持不变。

验收脚本建议：自建 lint:tokens（禁止裸色值/px）与 test:a11y（axe + Playwright 覆盖 12 组件 + 3 条主旅程）。

### 1.4 反模式清单（维度 1）

| 反模式 | 为什么错 | 正确做法 | 证据 |
|---|---|---|---|
| console.log 打印 token | 泄露凭据 + 噪音 | 构建期 drop_console；token 不打印 | 【实证】hmdp-vue3/src/stores/modules/user.js:10,12 |
| 裸 token 无 Bearer | 与后端协议不符 | authorization: Bearer 且判空 | 【实证】05 §6；request.js:31；请求头规范 §1 |
| 状态码魔法数字散落（0/1/2） | 不可维护易错 | TS 枚举 + 注释 | 【实证】05 §6；ShopDetail.vue:21-22,62-66 |
| 错误提示散落页面 try/catch | 口径漂移漏报 | axios 拦截器统一 ElMessage + NsToastCenter | 【实证】05 §2/§5；request.js:38-58 |
| 图标按钮无 aria-label | 屏幕阅读器只读出按钮 | 每个图标按钮补可访问名 | 【推导】WCAG 4.1.2 |

---

## 2. 现代响应式布局

### 2.1 现状—证据—建议—验收—反模式（总表）

| 子项 | NearSlot 现状 | 证据 | 具体建议 | 验收标准 | 反模式 |
|---|---|---|---|---|---|
| 断点体系 | 移动<768 / 平板 768–1199 / 桌面 ≥1200 三档 | 【实证】standards/frontend.md §3；03:57 | 保留三断点做布局级切换；组件级一律用容器查询 | 375/768/1024/1440 四档无横向滚动；组件在窄容器（320px 侧栏）自适应 | 组件靠 window.innerWidth 判断；固定 width:1200px |
| Mobile-first | 规范写移动优先但示例用 xs/sm/md/lg 栅格 | 【实证】standards/frontend.md §3 | 基础样式=移动，min-width 渐进增强；容器查询兜底 | CSS 首个规则集无桌面优先写法 | 先写桌面再用 max-width 降级 |
| 容器查询 CQ | 未提及 | 【推导】需新增 | NsSlotGrid/NsBookingSummary 用 @container (min-width) 决定列数/紧凑度 | 同组件置于宽/窄容器呈现不同布局，无需改 props | 视口断点代替容器断点致嵌入式视图错乱 |
| 流式排版 | 未提及 | 【推导】需新增 | clamp 实现 fluid font/spacing：font-size clamp(14px,1vw+12px,18px) | 320–1920px 连续缩放无跳变无溢出 | 每断点跳变字号；vw 直接做正文致小屏过小 |
| 触控目标 | ≥44×44px | 【实证】03:59 | 全交互元素 min 44×44（含时段格/图标/筛选 chip），相邻间距 ≥8px | 自动化量测所有可点击元素 bounding box ≥44px | 时段格 30px 高；用 i 标签加 click 当按钮 |
| 折叠屏/平板 | 无策略 | 【推导】需新增 | 600–1200 连续宽度用双列；渐进增强铰链 API；跨屏不丢 hold 计时 | 折叠展开/收起切换不重排流程、不重置 hold | 断点硬切致展开瞬间抖动；忽略横向安全区 |
| 预约 ≤3 taps | 规范称 3 次点击可达但无定义/验证 | 【实证】standards/frontend.md §3；03:58 | 定义 taps：首页→服务商详情→时段网格→确认；GEO/登录预填不额外计次；埋点验证 | 埋点 P50 ≤3 taps 从首页到已选时段；无中间弹层增计数 | 详情→选服务→选技师→选时段→确认 5 层 |
| 表格/弹窗响应式 | 表格 xs/sm 转卡片；弹窗 sm 以下全屏 Drawer | 【实证】03:58 | 继承；时段网格 sm 以下转日列表+横向时间轴而非缩成一团 | 375px 下时段信息完整可读可点 | 桌面表格直接缩放出现水平滚动 |

### 2.2 布局模式明细

| 模式 | 实现要点 | 证据/来源 |
|---|---|---|
| 导航 | xs<768 底部 Tab（继承 FootBar 五格）；md+ 顶部导航+侧栏常驻 | 【实证】05 §3.5；standards/frontend.md §3 |
| 列表→卡片 | Element Plus 表格 + breakpoint 渲染；触底分页（见 §4.3） | 【实证】03:58；ShopList.vue:162-171 |
| 时段网格 | 外层 role=radiogroup，格子 role=radio；容器查询决定 3/5/7 列 | 【推导】08 §2 |
| 全屏 Drawer | sm 以下弹窗转 Drawer；hold 倒计时在 Drawer 内 sticky 常驻 | 【实证】03:58 +【推导】08 §4 |
| 安全区 | padding env(safe-area-inset-bottom) 适配刘海屏 | 【推导】须真机验收 |

证据锚点：hmdp-vue3 的 search-bar / type-list / blog-list 加 scroll 事件（index.vue:99-126）展示移动 App 风格骨架，但其选择器用类、无语义标签、无 CQ，正是 NearSlot 要升级之处。

### 2.3 反模式清单（维度 2）

| 反模式 | 证据 | 正确做法 |
|---|---|---|
| 后端坐标硬编码前端 x=120.149993, y=30.334229 | 【实证】ShopList.vue:104-105 | 定位服务/店铺数据下发；用户授权后取 geo |
| 固定像素宽度容器 | 【实证】standards/frontend.md §3 明令禁止 | 只用 max-width + 百分比/grid |
| 触底判定无节流（首页） | 【实证】index.vue:70-83 对比 ShopList.vue:162 已 throttle | 统一 lodash-es throttle(leading+trailing)；isReachBottom 双保险保留 |
| 视图层换算散落 score/10 | 【实证】05 §3.2 | 集中在 formatter 并加注释 |

---

## 3. 用户旅程（状态机 + 验收）

### 3.1 主旅程：浏览→GEO→选时段→抢约→hold→支付→提醒→核销→评价

| 步骤 | 前端状态 | 触发/输入 | 成功出口 | 失败/边界 | 证据 |
|---|---|---|---|---|---|
| 1 浏览 | browse.idle→loading→ready | 首页加载 | 服务商列表（骨架→卡片） | 空态带换城市/筛选 CTA | 【实证】index.vue:18-48；standards/frontend.md §4 |
| 2 GEO | geo.locating→ready 或 denied | 授权定位/手选 | ≤5km 服务商 GEOSEARCH | denied→手动地址兜底 | 【实证】08 §7 |
| 3 选时段 | slot.selecting→selected | 点 NsSlotGrid 格 | 选中 slotId（String） | 已占用格禁用并说明原因 | 【推导】08 §2/§3 |
| 4 抢约 | grab.idle→grabbing→held 或 lost | 点立即预约 | 获 holdId（Lua 三检查） | 已占用/等级不足/GEO 外给具体文案 | 【实证】ShopDetail.vue:205-228；08 §9 三闸门 |
| 5 Hold | hold.active→warning→expired 或 converted | 倒计时 ≤支付 P99 | 进入支付 | 最后 60s 强提示；expired→释放并提示候补 | 【推导】08 §4 |
| 6 支付 | pay.pending→confirming→paid | 支付回调/SSE | active→booked（version+1） | 回调先校验 hold.status；竞态走退款补偿 | 【实证】08 §4 |
| 7 提醒 | reminder.scheduled→sent | 延迟队列 | 通知到达 | 控频去重 SETNX+TTL | 【实证】03 §4；08 §4 |
| 8 核销 | checkin.pending→checked 或 no_show | 到店扫码 | 核销成功 | 超时未到→no-show + 违约分 | 【实证】08 §8 |
| 9 评价 | review.editing→submitting→done | 提交 NsReviewForm | 评价落库 | 重复提交拦截 42902 | 【实证】standards/frontend.md §4；08 §8 |

主状态机（前端视图）：browse → geo → selecting → grabbing → 分支A held → paying → booked → reminder → checkin → review；分支B lost → waitlist.join；分支C error → recover。hold.active 到期转 hold.expired → slot.opened 触发候补提升。

主旅程验收标准（AC）：
1. AC-1 防重：连点立即预约 5 次，bookingInProgress 类标志只放行 1 次，网络请求数=1（继承 ShopDetail.vue:205-209），全屏 loading lock 生效（:210-216）。
2. AC-2 令牌前置：抢约前必先取 accessToken，失败不发下单（ShopDetail.vue:217-223）。
3. AC-3 异步确认：下单成功后轮询/订阅确认，超时给请稍后在订单页查看降级话术（ShopDetail.vue:83-91,228）。
4. AC-4 状态回查：成功后重查已预约状态并置灰（ShopDetail.vue:229-239）——服务端状态确认优先于本地乐观（05 §5）。
5. AC-5 触控：任何断点下从首页到已选时段 ≤3 taps（埋点 P50）。
6. AC-6 hold：倒计时与服务端 expiresAt 对齐（服务端时间校准），expired 后按钮不可点。

### 3.2 AI 助手旅程（自然语言预约）

| 阶段 | SSE 事件 | 前端状态 | 用户可见 | 验收 | 证据 |
|---|---|---|---|---|---|
| 建立会话 | meta{conversationId} | chat.connecting→ready | 保存会话 ID | meta 必须是首事件 | 【实证】SSE规范 §4.1 |
| 规划 | plan{round,tasks[]} | chat.planning | 步骤清单 | 每轮全量替换任务列表 | 【实证】SSE规范 §4.2 |
| 执行 | progress{stage,step} | chat.executing | RUNNING/COMPLETED/FAILED | 按 taskId 定位，只有 toolName 时按名更新 | 【实证】SSE规范 §4.3/§5 |
| 审批 | confirm{confirmId,tool,reason,arguments} | chat.awaiting_confirm | 确认/拒绝双按钮 | 高风险工具必须暂停；确认续流同协议 | 【实证】SSE规范 §4.4；Agent安全与审批设计 §6-8 |
| 终态 | EOF 或 error | chat.done 或 error | 回答/错误码 | EOF 为准，兼容历史 DONE 但后端不发 | 【实证】SSE规范 §3/§4.5 |

约束：自然语言预约必须走与手动相同的三闸门（GEO/等级/时间），Agent 不得绕过直接调 Lua（08 §9）；CONFIRM 审批用 CAS 防重复确认（Agent安全与审批设计 §7.1）；确认/拒绝按钮需键盘可达 + aria-describedby 说明将执行的操作。

### 3.3 取消 / 候补旅程

| 旅程 | 前端状态 | 关键约束 | 验收 | 证据 |
|---|---|---|---|---|
| 取消 | cancel.idle→confirming→cancelled | 状态机校验 CONFIRMED→CANCELLED；取消端点只标记可用+发 slot.opened 事件，不做提升重逻辑；幂等键 cancelId | 连点取消只发一次；取消后槽位置灰/候补位次更新 | 【实证】08 §6；standards/frontend.md §4 |
| 加入候补 | waitlist.idle→queueing | ZSet score=tsMs×1000+jitter；幂等键 userId+slotId | 重复加入返回原位次不插队尾 | 【实证】08 §5 |
| 候补提升 | waitlist.offered→claiming→held 或 expired | offer=10min hold；过期再提升下一位（循环） | 提升通知可达（SSE）；认领后进入 §3.1 的 hold→pay | 【实证】08 §5 |
| 位次查询 | waitlist.position | 前 100 名 SSE 实时，其余 30s 缓存轮询 | 位次变化 30s 内刷新 | 【实证】08 §5 |

### 3.4 错误 / 恢复旅程

| 故障 | 前端表现 | 恢复动作 | AC | 证据 |
|---|---|---|---|---|
| 401 superseded | 提示账号已在其它设备登录 | 读 X-Auth-Reason→单飞登出→跳登录 | 不同 reason 不同文案；不无限重试 | 【实证】请求头规范 §8/§9；standards/frontend.md §2 |
| 401 no-session | 提示请先登录 + redirect 回跳 | 清 AT/RT/用户信息；记录 redirect | 登录后回跳原页 | 【实证】请求头规范 §9.3；standards/frontend.md §4 |
| Refresh skipped | 无感 | 保持现有 Token，不写回旧值 | 不出现 Token 覆盖 | 【实证】请求头规范 §3.3/§7.1 |
| 抢约超时 | 降级话术 | 请稍后在订单页查看 + 订单页入口 | 不误报成功 | 【实证】ShopDetail.vue:83-91 |
| 网络断开 | 离线 banner | 重试队列；hold 计时以服务端为准 | 恢复后不重复提交 | 【推导】+【实证】SSE规范 §7 |
| SSE 错误事件 | NsSseAnswer error 态 | 显示 code+error，停止流 | error 后服务端结束流 | 【实证】SSE规范 §4.5/§7 |

未登录先拦截模式（05 §5）：进入需登录交互前主动判 token，给友好提示 + 200ms 后跳登录，不依赖 401 兜底（ShopDetail.vue:254-259 取消时先判 token）。NearSlot 统一为 NsAuthGuard。

### 3.5 反模式清单（维度 3）

| 反模式 | 证据 | 正确做法 |
|---|---|---|
| setTimeout 跳登录散落各处 | 【实证】ShopDetail.vue:257-259 | 统一 NsAuthGuard + redirect 记录 |
| 前端乐观更新覆盖服务端状态 | 【实证】05 §5（点赞以服务端确认为准，catch 才 ++） | 强一致状态一律服务端回查 |
| hold 过期用前端 setTimeout | 【推导】08 §4 明确禁止同步清理 | 服务端延迟队列 + 前端只展示倒计时 |

---

## 4. 合理的数据结构与算法

### 4.1 前端状态模型（总表）

| 数据域 | 前端结构 | 类型/键 | 证据 | NearSlot 建议 |
|---|---|---|---|---|
| 用户/会话 | Pinia store + persistedstate | store key Nearslot-User（对齐 Hmdp-User） | 【实证】standards/frontend.md §1；user.js:6,38 | AT 内存 + 短时 cookie；禁止 RT 存 localStorage；token 不 console |
| 服务端 ID | 字符串化 | String(voucherId) 作 map key | 【实证】ShopDetail.vue:25,38-39；request.js:12-23 | 后端 WRITE_NUMBERS_AS_STRINGS + 前端 json-bigint 双保险（03 §2） |
| 槽位选择 | Map<string, SlotVM> | key=slotId(string)，value 含 status/version/startsAt(UTC)/tzId | 【推导】08 §2 | status 用 TS 枚举：available/held/booked/disabled/past |
| 请求态 | TanStack Query QueryKey | 如 slots + shopId + date + geoHash | 【推导】新增，替代手写 Promise.all 状态 | 见 §4.2 |
| 防重标志 | ref(false) + 全屏 loading | seckillInProgress | 【实证】ShopDetail.vue:18,209,248 | 改名 bookingInProgress，统一 finally 复位 |
| 列表 | 数组 concat + 游标 | blogs.concat(data)；current++ | 【实证】index.vue:43,78；ShopList.vue:126,166 | 保留游标分页；深分页用 lastId 而非 LIMIT（03 §4） |

### 4.2 TanStack Query 缓存策略（建议引入，替代手写请求态）

| 查询 | QueryKey | staleTime | 失效时机 | 理由 |
|---|---|---|---|---|
| 服务商列表 | providers + geoHash + typeId | 60s | GEO 变更/下拉刷新 | 低频变更 |
| 时段网格 | slots + providerId + date | 0（实时） | 抢约/取消/hold 过期后 invalidateQueries | 槽位是稀缺资源，必须实时，禁止长缓存 |
| 已预约状态 | booked + userId | 0 | 支付/取消成功后失效 | 服务端确认为准（05 §5） |
| 候补位次 | waitlist + slotId + userId | 30s | SSE 推送时 setQueryData | 前 100 名走 SSE（08 §5） |

变更约定：grab/cancel/joinWaitlist 用 useMutation；onMutate 不乐观改槽位状态（防超卖幻觉）；onSuccess 后 invalidateQueries(slots)；防重用 mutation.isPending（替代裸布尔，ShopDetail.vue:18）。

### 4.3 虚拟列表与分页

| 场景 | 现状 | 建议 | AC | 证据 |
|---|---|---|---|---|
| 长列表（服务商/记录） | 触底分页 concat，DOM 无限增长 | >200 项接 tanstack/vue-virtual，或保留游标分页但单页 ≤20 | 滚动 1000 项 DOM 节点 <200 且无卡顿 | 【实证】index.vue:43；ShopList.vue:126；03 §4 |
| 触底双保险 | isReachBottom 标志 + throttle | 继承；首页补齐 throttle（当前缺失） | 快速滚动到底只触发一次加载 | 【实证】index.vue:12,74 vs ShopList.vue:162,99 |
| 日期/时段虚拟化 | 无 | 按日视图只渲染当前视口 + 横向虚拟列 | 30 天 × 48 槽仅渲染可见区域 | 【推导】08 §2 |

### 4.4 日期/时间数学与 DST（NearSlot 特有，两项目空白）

| 问题 | 数据表示 | 算法/库 | AC | 证据 |
|---|---|---|---|---|
| 槽位存储 | startsAt(UTC instant，BigInt 毫秒或 string) + tzId(IANA) + localWallTime | dayjs + useNow；服务端为准 | 同一槽位在 DST 切换日不重复/不漏 | 【实证】08 §7 槽位存 UTC+tzId，RRULE 写时展开 |
| 时区渲染 | Intl.DateTimeFormat(tzId) 显示本地墙钟 | 禁止 new Date(ms).toLocaleString()（依赖浏览器时区） | 切系统时区后显示与服务端 tzId 一致 | 【推导】08 §7 |
| RRULE | 写时展开为槽位行，查询期不展开 | 后端生成，前端只消费 | 前端不做 rrule 运行时计算 | 【实证】08 §2 |
| Hold 倒计时 | expiresAt - serverNow，服务端时间校准 offset | useNow + offset | 客户端改系统时钟不影响剩余秒数 | 【推导】08 §4 |
| BigInt 精度 | ID 全程 string；金额用整数分避免浮点 | json-bigint storeAsString；cents:number | 雪花 ID 无精度丢失；金额无 0.1+0.2 问题 | 【实证】request.js:12-23；05 §2；standards/frontend.md §2 |

证据锚点：hmdp-vue3 的 formatTime 用 dayjs(time).format(YYYY年MM月DD日)（format.js:1-4）丢失时区语义，NearSlot 必须升级为带 tzId 的格式化。

### 4.5 时段网格 / 座位图算法

| 算法 | 输入 | 输出 | 复杂度 | AC | 证据 |
|---|---|---|---|---|---|
| 槽位状态归并 | slots[] + bookedSet + holdSet | SlotVM[]（含 disabled 原因） | O(n) | 已占用格不可点且 tooltip 说明 | 【推导】08 §2/§3 |
| 技师时间轴重叠检测 | ZSet(score=startsAt, value=endsAt) | 冲突集 start<newEnd AND end>newStart | Lua ZRANGEBYSCORE | 同技师相邻不重叠（含 travelBuffer） | 【实证】08 §3/§7，前端只展示冲突结果，判断在 Lua |
| 座位图渲染 | layout(SVG path 数组) + occupied[] | 可交互 SVG | O(n) | 占用座位非纯颜色区分（图案+aria） | 【推导】08 §7 |
| 候补公平排序 | tsMs×1000+jitter | ZRANK | — | 同毫秒网络邻近用户不插队 | 【实证】08 §5 |

关键约束：前端不做并发裁决——超卖/重复预约的正确性在 Lua + DB 唯一约束（08 §2/§3），前端只准确呈现服务端返回的 status 与 version，禁止本地先占位再说。

### 4.6 反模式清单（维度 4）

| 反模式 | 证据 | 正确做法 |
|---|---|---|
| 用 Number 接雪花 ID | 【实证】03 §2 必须双保险 | json-bigint + String() |
| 金额浮点运算 | 【推导】 | 整数分 |
| 前端时区 toLocaleString | 【实证】format.js:4 有缺陷 | Intl + tzId |
| 无限 DOM 列表 | 【实证】index.vue:43 | 虚拟列表/分页上限 |
| 自写 throttle 丢尾沿 | 【实证】scroll.js:2-11（timer 存在即 return） | lodash-es throttle(leading+trailing) |
| 视图层魔法换算散落 | 【实证】05 §3.2 score/10 | 集中 formatter + 注释 |

---

## 5. SSE 流式交互

### 5.1 事件契约（后端为准，前端消费）

| 事件 | 字段 | 前端动作 | 证据 |
|---|---|---|---|
| meta | conversationId | 首事件保存会话 ID | 【实证】SSE规范 §4.1 |
| plan | round, tasks[{id,description,toolName,type,status}] | 每轮全量替换任务清单 | 【实证】SSE规范 §4.2 |
| progress | stage(planning/executing/merging) 或 step(taskId,toolName,status) | 按 taskId/toolName 更新步骤 | 【实证】SSE规范 §4.3/§5 |
| confirm | confirmId, tool, reason, arguments | 暂停，渲染确认/拒绝 | 【实证】SSE规范 §4.4 |
| error | error, code | 显示错误并结束流 | 【实证】SSE规范 §4.5 |
| 普通文本 | data 后跟文本 | token 追加到回答 | 【实证】SSE规范 §3 |
| 终止 | EOF（emitter.complete） | 结束；兼容历史 DONE 但后端不发 | 【实证】SSE规范 §3/§7 |

工具 status：RUNNING/COMPLETED/FAILED/SKIPPED（SSE规范 §5）。

### 5.2 前端读取实现要求（后端规范 §8 原文映射 + 深化）

| 要求 | 实现 | 证据 |
|---|---|---|
| fetch ReadableStream | fetch(POST, headers, body, credentials include) 读 response.body.getReader | 【实证】SSE规范 §8.1；请求头规范 §4 |
| 认证头 | authorization: Bearer + Refresh-Token（请求头优先，Cookie 兜底） | 【实证】请求头规范 §1/§2/§9.1 |
| 行解析 | 按空行分帧，逐行剥 data 前缀，拼完整事件 | 【实证】SSE规范 §8.2 |
| meta 先建会话 | 收到 meta 立即存 conversationId | 【实证】SSE规范 §8.3 |
| 类型分发 | JSON 事件按 type switch；普通文本追加 | 【实证】SSE规范 §8.4-5 |
| EOF 结束 | reader.done 时置 done；兼容 DONE | 【实证】SSE规范 §8.6-7 |

### 5.3 重连 / 生命周期状态机

connecting →（meta）→ streaming →（confirm）→ awaiting_confirm →（确认/拒绝）→ streaming；网络断开 → reconnect（退避 1s/2s/4s，最多 3 次，带 conversationId）→ 成功回 streaming；error 或 EOF → done/error。

| 场景 | 行为 | AC | 证据 |
|---|---|---|---|
| 初始化异常 | 服务端发 error 后 complete | 前端展示错误，不空转 | 【实证】SSE规范 §7 |
| 模型重试耗尽 | error 后 complete | 同上 | 【实证】SSE规范 §7 |
| 客户端断开 | 服务端 safeSend 忽略 IOException | 前端不崩，可重连 | 【实证】SSE规范 §7 |
| CONFIRM 暂停 | 发 confirm 并结束当前 SSE 等待续流 | 前端确认后新建 SSE 会话续流（带 conversationId + 审批决策） | 【实证】SSE规范 §7；Agent安全与审批设计 §7.2 |
| 流中错误 | 不回退 JSON | 前端不解析 JSON body | 【实证】SSE规范 §7 |
| 重连 | 带 conversationId 续 | 3 次退避后失败→提示用户，不无限重连 | 【推导】+【实证】请求头规范 §9.3 |

### 5.4 流式可访问性（a11y）

| 要求 | 实现 | AC | 证据 |
|---|---|---|---|
| 增量文本不刷屏 | 回答容器 aria-live=polite + aria-atomic=false，节流播报（如每 500ms） | 屏幕阅读器不逐 token 朗读 | 【推导】WCAG 4.1.3 |
| 规划/步骤可读 | 任务清单语义 ol，状态变更用 aria-label 更新 | 步骤状态变化可被读出 | 【实证】SSE规范 §4.2-3 +【推导】 |
| confirm 强提示 | role=alertdialog，焦点移入，aria-describedby 说明 tool+arguments | 确认需显式操作，键盘可拒 | 【实证】Agent安全与审批设计 §6 +【推导】 |
| error 播报 | role=alert | 错误立即播报 | 【推导】 |
| 停止生成 | 提供停止按钮（AbortController），aria-label=停止生成 | 可中断流 | 【推导】 |
| reduced-motion | token 归零（见 §1.1） | 打字机动画可关闭 | 【推导】 |

### 5.5 反模式清单（维度 5）

| 反模式 | 证据 | 正确做法 |
|---|---|---|
| 依赖 DONE 作为唯一终止 | 【实证】SSE规范 §3 后端不发 | EOF 为准，兼容即可 |
| 401 时无限重连 | 【实证】请求头规范 §10.4 | 3 次退避 + 单飞登出 |
| Bearer null | 【实证】请求头规范 §10.1 | JWT 形状校验后才写 |
| 流中错误回退 JSON 解析 | 【实证】SSE规范 §7 | 只读 SSE 事件 |
| 逐 token 触发 aria-live | 【推导】 | 节流播报 |
| 手工拼 JSON 事件 | 【实证】SSE规范 §9.2 后端约束，前端对称 | 统一序列化/解析函数 |

---

## 6. 落地优先级与证据索引

| 优先级 | 维度 | 首要动作 | 阻塞关系 |
|---|---|---|---|
| P0 | 4 数据结构 | 落地 json-bigint 双保险 + TS 枚举 + TanStack Query 槽位实时查询 | 阻塞 1/2/3 正确性 |
| P0 | 3 主旅程 | 防重三元组 + hold 倒计时 + 服务端状态回查 | 阻塞上线 |
| P1 | 1 UX 标准 | 冻结 token + 12 组件契约 + axe 扫描 | 阻塞设计验收 |
| P1 | 2 响应式 | mobile-first + CQ + 3-tap 埋点 | 阻塞移动端发布 |
| P1 | 5 SSE | 事件状态机 + 重连 + confirm UI | 阻塞 AI 助手发布 |
| P2 | 4 DST/虚拟列表 | 固定时区 Testcontainers 用例 + 虚拟化 | 依真实排班数据（08 §7 证据缺口） |

关键证据文件速查：

| 文件 | 关键行 | 用途 |
|---|---|---|
| hmdp-plus/hmdp-vue3/src/utils/request.js | 12-23, 30-32, 38-58 | json-bigint、裸 token、统一错误出口 |
| hmdp-plus/hmdp-vue3/src/views/shop/ShopDetail.vue | 18-26, 29-48, 83-91, 205-250, 254-259 | 防重三元组、轮询降级、服务端回查、未登录拦截 |
| hmdp-plus/hmdp-vue3/src/views/shop/ShopList.vue | 99-106, 121-171 | 坐标硬编码反例、throttle 触底双保险 |
| hmdp-plus/hmdp-vue3/src/views/index.vue | 12, 38, 43, 70-83, 99-126 | concat 分页、isReachBottom、无节流反例 |
| hmdp-plus/hmdp-vue3/src/utils/scroll.js | 2-11 | 自写 throttle 丢尾沿 |
| hmdp-plus/hmdp-vue3/src/utils/format.js | 1-4 | dayjs 无时区语义 |
| hmdp-plus/hmdp-vue3/src/stores/modules/user.js | 6, 10-12, 38 | token console 泄露、persist key |
| PickNear/md/agent/SSE后端实现规范.md | §3-§9 | SSE 帧/事件/生命周期/前端要求 |
| PickNear/md/规范/请求头设计规范.md | §1-§10 | Bearer、RT 双通道、401 分支、禁止事项 |
| NearSlot/docs/research/08-nearslot-domain-research.md | §1-§9 | 槽位/Hold/候补/DST/三闸门领域基线 |
| NearSlot/docs/research/03-framework-frontend.md | §3-§5 | 断点/44px/WCAG/数据结构/时序 |
| NearSlot/docs/research/05-frontend-ux-research.md | §1-§6 | 工程底座/防重/服务端确认/反模式 |
| NearSlot/docs/standards/frontend.md | §1-§5 | 规范基线（token/精度/响应式/UX/a11y） |

证据缺口（须在 Phase 1 补齐）：折叠屏/容器查询/流式排版/DST 正确性/虚拟列表阈值均无源码实证，属【推导】；需用 Testcontainers + 固定时区用例 + 真机埋点验证后再转【实证】。见 08 §7 证据缺口。

—— 完 ——
