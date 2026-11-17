/** 认证接口（docs/api/design.md §4） */
import http from "@/utils/request";
import type { LoginPayload, LoginResult, UserLevel } from "@/types/api";

export interface RefreshResult {
  accessToken: string;
  userId?: string;
  level?: UserLevel;
}

/** 账号密码登录：成功后 AT 在响应体，RT 由后端 Set-Cookie 下发 */
export function login(payload: LoginPayload): Promise<LoginResult> {
  return http.post<LoginResult>("/auth/login", payload);
}

/** 登出：后端使 RT Cookie 失效并顶替当前 AT */
export function logout(): Promise<void> {
  return http.post<void>("/auth/logout");
}

/** 主动刷新 AT（一般由拦截器自动完成，此处供页面手动兜底） */
export function refresh(): Promise<RefreshResult> {
  return http.post<RefreshResult>("/auth/refresh");
}

/** 当前登录用户信息 */
export function fetchProfile(): Promise<{ userId: string; username: string; level: UserLevel }> {
  return http.get("/auth/profile");
}
