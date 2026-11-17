---
title: 前端与 UX 规范
status: current
source_of_truth:
  - ../../docs/standards/frontend.md
updated: 2026-11-16
---

# 前端与 UX 规范

## 1. 技术栈与工程

- Vue 3（Composition API + setup 语法）+ TypeScript 6 strict + Vite + Element Plus（unplugin auto-import）+ Pinia（persistedstate，store 键 Nearslot-User）。
- **Vite+（vite-plus 1.6）统一工具链**：`vp dev` / `vp build` / `vp check`（= fmt + lint + type-check 一次过）/ `vp test` / `vp fmt` / `vp lint`；底层为 vite 8（rolldown）+ oxlint + oxfmt + vitest 5，取代 ESLint/Prettier 独立配置。
- 提交钩子：`vp hooks`（替代手写 husky+lint-staged）；CI 统一跑 `vp check` + `vp test run` + `vp build`。
- 目录：src/{api,components,composables,router,stores,views,utils,styles}；api/ 按域拆文件（对齐 hmdp-vue3 voucher.js 模式：一条业务链一个文件）。

## 2. 数据与精度

- axios：baseURL /api + timeout 10s；transformResponse 用 json-bigint(storeAsString) 防雪花 ID 精度丢失（ADR-012 前端半边）。
- 拦截器：请求注入 authorization: Bearer（值判空防御，禁止 Bearer null）；401 按 X-Auth-Reason 分支——superseded 静默刷新重放、no-session 跳登录；刷新失败 X-Token-Refresh:failed 只提示一次，禁止无限重试。
- RT 双通道：请求头 Refresh-Token 优先，Cookie 兜底（后端写入，前端不读 localStorage 存 RT）。

## 3. 响应式布局（断点继承 hmdp-vue3 体系）

| 断点 | 宽度 | 布局 |
|---|---|---|
| 移动 | <768px | 单列，底部导航，列表卡片全宽 |
| 平板 | 768–1199px | 双列/侧边栏可折叠 |
| 桌面 | ≥1200px | 顶部导航 + 侧边栏 + 内容栅格 24 栏 |

- 组件用 Element Plus 栅格（el-row/el-col :xs :sm :md :lg）+ CSS Flex/Grid；禁止固定像素宽度容器（max-width 除外）。
- 图片懒加载 + 骨架屏（列表加载态统一 skeleton 组件）。

## 4. UX 规范

- 反馈三件套：操作成功 ElMessage 成功态、失败带 code 文案、加载全局 v-loading + 按钮级 loading。
- 表单：标签左置（桌面）/上置（移动）；错误即时校验；提交防双击（对应后端 @Idempotent 42902 文案「请勿重复提交」）。
- 空态：统一 Empty 组件（插画+引导按钮）；错误页 403/404 独立路由。
- 路由守卫：未登录重定向 /login 并记录 redirect 回跳。

## 5. 可访问性与性能

- 语义化标签 + alt 属性 + 键盘可达；颜色对比 ≥4.5:1。
- 首屏 LCP <2.5s：路由级代码分割、Element Plus 按需引入、静态资源缓存头。
