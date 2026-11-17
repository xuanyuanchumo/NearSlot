/**
 * 鉴权上下文工具
 *
 * 约束（docs/standards/frontend.md §2、docs/api/design.md §4）：
 * - AT 存 Pinia（persistedstate 持久化），请求头名小写 authorization: Bearer <AT>；
 * - AT 为空时绝不写 authorization 头，杜绝 "Bearer null"；
 * - RT 只能通过 请求头 Refresh-Token（内存态）或 HttpOnly Cookie 传递，前端永不落地 localStorage；
 * - 本文件的 memoryRefreshToken 只活在内存，刷新页面即失效，属于双通道中的"请求头优先"那一半。
 */
import { pinia } from "@/stores";
import { useUserStore } from "@/stores/user";
import type { UserLevel } from "@/types/api";

/** 内存态 RT（可空）：仅当后端以响应体返回 RT 时才使用，默认走 HttpOnly Cookie */
let memoryRefreshToken = "";

export function getAccessToken(): string {
  return useUserStore(pinia).accessToken;
}

export function getUserId(): string {
  return useUserStore(pinia).userId;
}

export function getLevel(): UserLevel {
  return useUserStore(pinia).level;
}

/** 是否已登录（AT 非空即视为已登录，服务端仍会二次校验） */
export function isLoggedIn(): boolean {
  return getAccessToken().trim().length > 0;
}

/** 写入会话（刷新成功后同步 userId / level） */
export function applyAuthSession(
  accessToken: string,
  profile?: { userId?: string; level?: UserLevel },
): void {
  useUserStore(pinia).setToken(accessToken, profile);
}

/** 清空会话（AT / userId / level 全清；RT 由后端 Cookie 失效控制） */
export function clearAuth(): void {
  setMemoryRefreshToken(null);
  useUserStore(pinia).logout();
}

export function getMemoryRefreshToken(): string {
  return memoryRefreshToken;
}

export function setMemoryRefreshToken(token: string | null): void {
  memoryRefreshToken = token ?? "";
}

/**
 * 构造鉴权请求头。
 * authorization 头名小写；值为空时整条头不出现（禁止 Bearer null / Bearer undefined）。
 */
export function buildAuthHeaders(): Record<string, string> {
  const headers: Record<string, string> = {};
  const token = getAccessToken();
  if (token && token.trim().length > 0) {
    headers.authorization = `Bearer ${token.trim()}`;
  }
  if (memoryRefreshToken) {
    headers["Refresh-Token"] = memoryRefreshToken;
  }
  return headers;
}
