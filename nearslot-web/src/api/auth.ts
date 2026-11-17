/** 认证接口（docs/api/design.md §4） */
import http from "@/utils/request";
import type { UserLevel } from "@/types/api";

/**
 * 登录请求体。
 *
 * 2026-11-17 审计修复（Blocker）：后端 LoginRequest 的字段是
 * `phone`（@NotBlank）+ `password` + 可选 `code` + `type`，
 * 没有 username 字段——前端传 username 会被参数校验直接打回 400。
 */
export interface LoginPayload {
  phone: string;
  password?: string;
  code?: string;
  /** password / code */
  type?: "password" | "code";
}

/** 登录响应体（RT 由后端 HttpOnly Cookie 下发，前端永不落地） */
export interface LoginResult {
  accessToken: string;
  userId: string;
  level: UserLevel;
}

export interface RefreshResult {
  accessToken: string;
}

/** 账号密码登录：成功后 AT 在响应体，RT 由后端 Set-Cookie 下发 */
export function login(payload: LoginPayload): Promise<LoginResult> {
  return http.post<LoginResult>("/auth/login", payload);
}

/** 下发验证码（dev 环境响应体回显；生产接短信网关后不回显） */
export function sendCode(phone: string): Promise<string> {
  return http.post<string>("/auth/code", null, { params: { phone } });
}

/** 登出：后端清理四键并失效 RT Cookie */
export function logout(): Promise<void> {
  return http.post<void>("/auth/logout");
}

/** 主动刷新 AT（一般由拦截器自动完成，此处供页面手动兜底） */
export function refresh(): Promise<RefreshResult> {
  return http.post<RefreshResult>("/auth/refresh");
}

/**
 * 修改密码（改密后后端 bump version，所有旧 AT 立即失效）。
 *
 * 说明：原文件里的 fetchProfile() 指向 /auth/profile，后端从未实现该端点
 * （实测 404），属于死代码，已删除——用户信息随登录/刷新响应一起下发。
 */
export function changePassword(oldPassword: string, newPassword: string): Promise<void> {
  return http.post<void>("/auth/password", { oldPassword, newPassword });
}
