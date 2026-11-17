/**
 * 会话失效跳转（请求拦截器 / SSE 客户端共用）
 *
 * 延迟 import router，避免 request.ts <-> router <-> store 的静态循环依赖。
 * 单飞保护：并发多个 401 只跳一次、只提示一次。
 */
let redirecting = false;

export interface GotoLoginOptions {
  /** 登录后回跳地址；默认取当前路由 */
  redirect?: string;
  /** 跳转前提示文案；为空则静默跳转 */
  message?: string;
}

export async function gotoLogin(options: GotoLoginOptions = {}): Promise<void> {
  if (redirecting) return;
  redirecting = true;
  try {
    const { default: router } = await import("@/router");
    const current = router.currentRoute.value;
    const redirect = options.redirect ?? current.fullPath;
    if (current.name === "login") return;
    const query: Record<string, string> = {};
    if (redirect && redirect !== "/" && redirect !== "/login") {
      query.redirect = redirect;
    }
    await router.replace({ name: "login", query });
  } finally {
    // 留一个短窗口，防止同一批并发请求在 replace 完成前重复触发
    window.setTimeout(() => {
      redirecting = false;
    }, 800);
  }
}

/** 仅供测试或登出流程重置跳转状态 */
export function resetRedirecting(): void {
  redirecting = false;
}
