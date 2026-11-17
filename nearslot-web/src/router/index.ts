/**
 * 路由表 + 全局守卫
 *
 * 约定：
 * - 视图全部懒加载（路由级代码分割，首屏 LCP 优化）；
 * - meta.requiresAuth 标记需登录页面，守卫读 pinia 中的 AT；
 * - 未登录重定向 /login 并记录 redirect 回跳（docs/standards/frontend.md §4）。
 */
import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";
import { isLoggedIn } from "@/utils/auth";

const routes: RouteRecordRaw[] = [
  {
    path: "/",
    name: "shop-list",
    component: () => import("@/views/ShopListView.vue"),
    meta: { title: "附近服务商", requiresAuth: false },
  },
  {
    path: "/login",
    name: "login",
    component: () => import("@/views/LoginView.vue"),
    meta: { title: "登录", requiresAuth: false },
  },
  {
    path: "/shops/:id",
    name: "shop-detail",
    component: () => import("@/views/ShopDetailView.vue"),
    meta: { title: "服务商详情", requiresAuth: false },
  },
  {
    path: "/appointments",
    name: "appointments",
    component: () => import("@/views/AppointmentListView.vue"),
    meta: { title: "我的预约", requiresAuth: true },
  },
  {
    path: "/agent",
    name: "agent",
    component: () => import("@/views/AgentChatView.vue"),
    meta: { title: "AI 助手", requiresAuth: true },
  },
  {
    path: "/:pathMatch(.*)*",
    name: "not-found",
    component: () => import("@/views/NotFoundView.vue"),
    meta: { title: "页面不存在", requiresAuth: false },
  },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(_to, _from, savedPosition) {
    return savedPosition ?? { top: 0 };
  },
});

router.beforeEach((to) => {
  const authed = isLoggedIn();

  // 已登录访问登录页 -> 回到首页或回跳目标
  if (to.name === "login" && authed) {
    const redirect = typeof to.query.redirect === "string" ? to.query.redirect : "/";
    return { path: redirect };
  }

  if (to.meta.requiresAuth && !authed) {
    return { name: "login", query: { redirect: to.fullPath } };
  }

  return true;
});

router.afterEach((to) => {
  const title = typeof to.meta.title === "string" ? to.meta.title : "";
  const appTitle = import.meta.env.VITE_APP_TITLE || "NearSlot 时段预约";
  document.title = title ? `${title} · ${appTitle}` : appTitle;
});

export default router;
