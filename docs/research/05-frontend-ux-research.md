---
title: 前端与体验深度研究（hmdp-vue3 全量源码）
status: current
source_of_truth:
  - ../../docs/research/05-frontend-ux-research.md
updated: 2026-09-05
---

# 研究报告 05：hmdp-vue3 前端与 UX 深读（95 文件全量）

> 第二轮深读成果。第一轮（03 报告）覆盖架构与选型；本报告逐文件读完 src/ 全部 95 个文件，
> 提炼可直接复用的 UX 模式、必须规避的反面教材，以及 NearSlot 前端（Vue3+TS）的落地建议。

## 1. 工程底座

| 层 | 实现 | NearSlot 结论 |
|---|---|---|
| 构建 | Vite 6.2.4；AutoImport+Components(ElementPlusResolver) 按需；alias `@`→src；dev proxy `/api`→后端并 rewrite 去前缀 | 继承按需注册 + alias + proxy |
| 入口 | main.js：createApp→pinia→router→mount；**额外全量引 element-plus/dist/index.css**（保证 ElLoading 遮罩样式可用——按需注册的坑） | 按需引入时必须验证遮罩/指令类样式，缺了就补全量 css |
| 路由 | createWebHistory；`/` redirect `/index`；11 条路由全部 `() => import()` 懒加载；动态参数 `:id?` | 全量懒加载继承 |
| 状态 | Pinia 3 setup 式 store + pinia-plugin-persistedstate（persist:true 持久 token/userInfo） | 继承；但 token 持久化到 localStorage 是安全债，NearSlot 改 cookie+内存双轨 |
| 请求 | axios baseURL `/api` timeout 10000；**transformResponse=JSONbig({storeAsString:true}).parse**；响应拦截 401→跳登录 | JSON 精度方案继承（见 §3） |

## 2. 请求层关键决策

`utils/request.js` 三件事：

1. **Long 精度**：`transformResponse: [data => JSONbig({storeAsString: true}).parse(data)]`——
   雪花 ID 全程变字符串，前端不再有 `(2^53)` 精度丢失。非 JSON 响应原样返回。
2. **请求拦截**：`if (token) config.headers.Authorization = \`\${token}\``——**裸 token，无 Bearer 前缀**
   （与后端 UUID Session 配套的弱约定）。NearSlot 用 PickNear 的 `Bearer <access_token>` 规范。
3. **响应拦截**：`status !== 200` → ElMessage.error(后端 errorMsg)；error 分支 401→"请先登录"+跳登录，其余统一 ElMessage。
   错误提示收口在拦截器一层完成，页面代码零 try/catch 提示——**统一错误出口**是可复用模式。

## 3. 业务页深读结论

### 3.1 ShopDetail（秒杀 UX 全链，最核心）
- **ID 键全部 String 化**：`purchasedMap = ref({})` 以 `String(voucherId)` 为键，配合 json-bigint 字符串 ID——跨端 ID 一致性约定。
- **三态订阅**：`subscribeStatusMap` 0 未订阅 / 1 已订阅 / 2 自动发券成功（双保险把"已订阅"标成"已购"）。
- **批量状态预取**：`refreshPurchaseStatus` = Promise.all 逐券 `getVoucherOrderIdByVoucherId`；
  未登录直接 return（避免触发 401 重定向风暴）；单项失败静默（单券失败不拖垮整页）。
- **下单主流程 seckill(v)**：未登录 → error + 200ms 后跳 /login → isNotBegin/isEnd/stock<1/isPurchased 前置校验 →
  `seckillInProgress` 布尔**防重复点击** → ElLoading.service(fullscreen+lock+text"正在确认订单") →
  ①issueSeckillAccessToken（令牌前置授权）→ ②seckillVoucher(v.id, accessToken) → ③成功才 pollSeckillOrder →
  成功后 `getVoucherOrderIdByVoucherId` 复核置灰 → finally 复位标志+loading.close()。
- **订单轮询**：`pollSeckillOrder(orderId, {delay: 800, timeoutMs: 11000})` while 循环；
  成功 → success("抢购成功")；超时 → warning("确认订单超时，请稍后在订单页查看")——**异步落库的前端配合是"轮询+超时降级话术"**。
- **取消**：cancelVoucher 本地 purchasedMap=false + queryVoucher 刷新库存（本地状态与服务端刷新并行）。

### 3.2 列表与首页（分页与防重）
- index.vue：热博客 `concat` 追加分页；`b.images.split(',')[0]` 取首图；
  onScroll 触底判定 `scrollTop+offsetHeight > scrollHeight && !isReachBottom` 才 `current++`——**isReachBottom 标志防重复触发**。
- ShopList.vue：排序条（类型下拉+距离 x/y+人气+评分）；`el-rate disabled :value="s.score/10"`（后端 10 分制→5 星制换算放在视图层）；
  距离 <1000m 显示 m 否则 km；onScroll 用 throttle 包裹。
- **addLike→queryBlogById 服务端确认点赞状态**（catch 里才做 ````blog.liked++```` 兜底）——
  **服务端状态确认优先于本地乐观更新**，弱网下不会出现点赞数与后端漂移。

### 3.3 BlogDetail（手势轮播）
- 手写触摸轮播：sensitivity 60px、resistance 0.3 阻尼、start/move 手势状态机，不引第三方库。
- initData：getBlogById → images split → nextTick → initSwiper →
  `Promise.all([queryShopById, queryLikeList, queryLoginUser])` 并行请求（不串行等待）。

### 3.4 登录注册
- el-form rules：phone required+`/^1[3-9]\d{9}$/`、password required+min 6、radio 必选；validate→userLogin→setToken→push /index。
- ⚠️**反面教材**：表单硬编码测试账号 13838411438/123456。NearSlot 规定：测试凭据只进 .env.test，禁止进源码。

### 3.5 布局骨架
- FootBar 五格底部导航（首页/地图/中央加号/消息/我的），activeBtn prop 高亮 + toPage(i) router.push。
- 移动端 App 风格：底部导航+全屏页+无浏览器滚动条污染（每页自管滚动容器）。

## 4. 可复用工具

| 工具 | 实现 | 评价 |
|---|---|---|
| scroll.js | throttle=setTimeout 尾沿节流（timer 存在即 return）；getScrollTop/setScrollTop 兼容 documentElement/body | 够用但丢尾沿后续触发；NearSlot 用 lodash-es throttle（leading+trailing） |
| format.js | formatTime=dayjs('YYYY年MM月DD日')，dayjs 从 element-plus 转引 | NearSlot 显式依赖 dayjs |

## 5. UX 模式清单（NearSlot 前端规范输入）

1. **防重三元组**：in-progress 标志 + 全屏 Loading(lock) + finally 复位——所有提交类操作标配。
2. **异步结果轮询**：短轮询（800ms/11s 上限）+ 超时降级话术；NearSlot 预约确认链沿用（可升级 SSE）。
3. **未登录先拦截**：进入需要登录的交互前主动判 token，给出友好提示+延迟跳转，不依赖 401 兜底。
4. **服务端状态确认 > 本地乐观**：点赞/已购/库存等强一致状态以服务端回查为准。
5. **ID 全程 String**：json-bigint storeAsString + 业务键 String 化，跨端一致。
6. **错误出口唯一**：axios 拦截器统一 ElMessage；页面只处理业务分支。
7. **触底分页双保险**：isReachBottom 标志 + throttle。
8. **首图约定**：`images.split(',')[0]`——多图逗号分隔存库的读取约定，NearSlot 沿用并文档化。

## 6. 反面教材（NearSlot 明令禁止）

1. Authorization 裸 token（无 Bearer 前缀）——升级为 PickNear 请求头规范。
2. token 存 localStorage + console.log 调试残留。
3. 硬编码测试账号进源码。
4. 后端坐标硬编码在前端（杭州 x/y 写死在 ShopList）——应由定位服务或店铺数据下发。
5. 状态码魔法数字散落（subscribeStatus 0/1/2）——NearSlot 用枚举 + 注释。
