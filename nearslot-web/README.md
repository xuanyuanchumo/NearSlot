---
title: NearSlot 前端（nearslot-web）
status: current
source_of_truth:
  - README.md
updated: 2026-11-16
---

# NearSlot 前端（nearslot-web）

> 前后端分离（ADR-015）：后端 `nearslot-app`（Spring Boot 4）只暴露 REST + SSE，前端独立构建并由 Nginx 托管 + 反代 `/api`。
> 工具链：**Vite+（vite-plus 1.6）** 统一 dev / build / check / test（ADR-017）。

## 1. 技术栈

| 层     | 选型                                             | 说明                                                |
| ------ | ------------------------------------------------ | --------------------------------------------------- |
| 框架   | Vue 3.5（`<script setup lang="ts">`）            | Composition API                                     |
| 语言   | **TypeScript ~6.0.3（strict）**                  | `noUnusedLocals/Parameters`、`verbatimModuleSyntax` |
| 工具链 | **Vite+ 1.6**（内置 vite 8 + rolldown，ADR-017） | 单一 `vp` 入口                                      |
| 质量   | **oxlint + oxfmt + vitest 5**（由 Vite+ 内置）   | `vp check` / `vp test`                              |
| UI     | Element Plus 2.9 + `@element-plus/icons-vue`     | 组件与 API 按需自动引入                             |
| 状态   | Pinia 3 + `pinia-plugin-persistedstate` 4        | 仅持久化 AT/userId/level                            |
| 路由   | vue-router 4                                     | 懒加载 + `meta.requiresAuth` 守卫                   |
| HTTP   | axios 1.8 + `json-bigint(storeAsString)`         | 雪花 ID 精度保护                                    |

## 2. 命令（Vite+ 原生）

```bash
pnpm install      # 或 vp install
vp dev            # 开发服务器 http://127.0.0.1:5173，/api 代理到 8081
vp build          # 生产构建 -> dist/
vp check          # 一次性：格式化 + lint + 类型检查（CI 门禁）
vp check --fix    # 自动修复格式
vp test run       # 单元测试
vp fmt / vp lint  # 单独执行格式化 / lint
vp toolchain      # 查看 Vite+ 内部组件版本
```

> 语义提醒：`vp test` 是 Vite+ 内置命令；若要跑 package.json 里的 `test` 脚本，用 `vpr test`。

## 3. 目录结构

```
nearslot-web/
├── src/
│   ├── api/          接口封装（auth/shop/appointment/agent）
│   ├── components/   SlotGrid（时段栅格）、PageState（加载/空/错）
│   ├── router/       路由与鉴权守卫
│   ├── stores/       Pinia（user）
│   ├── styles/       设计令牌 CSS 变量
│   ├── types/        api.ts（ID 一律 string）
│   ├── utils/        request（单飞刷新）、sse（ReadableStream）、json、format
│   └── views/        Login / ShopList / ShopDetail / AppointmentList / AgentChat / NotFound
├── nginx 配置见 ../nginx/nearslot.conf
└── pnpm-workspace.yaml  Vite+ catalog + overrides
```

## 4. 关键约定（与后端契约一致）

- **鉴权头**：小写 `authorization: Bearer <AT>`；AT 为空时**不发送该头**（避免 `Bearer null`）。
- **刷新**：响应头 `X-Token-Refresh: ok|skipped|failed`；401 按 `X-Auth-Reason`（`superseded`/`no-session`）分别处理；刷新用**模块级单飞 Promise**，防并发风暴。
- **精度**：后端 `WRITE_NUMBERS_AS_STRINGS` + 前端 `json-bigint` 双保险（ADR-012）；**所有 ID 用 string 比较**。
- **响应体**：`{ code, message, data, timestamp }`；业务失败走 200 + 501xx，前端按 code 分流。
- **SSE**：事件 `meta / plan / progress / error / confirm`；用 fetch + ReadableStream（可带 Authorization），断线带 `Last-Event-ID` 重连 3 次退避。
- **刷新令牌**：存 HttpOnly Cookie，**永不进 localStorage/Pinia persist**。

## 5. 验证状态

| 检查                        | 结果                                                                   |
| --------------------------- | ---------------------------------------------------------------------- |
| `vp check`（fmt+lint+类型） | ✅ 通过                                                                |
| `vp build`                  | ✅ 产出 dist/                                                          |
| `vp test run`               | ✅ 5/5 通过                                                            |
| `vp toolchain`              | vite-plus 1.6.0 / vite 8.3.7 / vitest 5.1.0 / oxlint 1.93 / oxfmt 0.78 |

> 版本锁定以 `package.json` 为准（TypeScript `~6.0.3`）；若需回退 TS 5，改版本号即可，无需改源码。
