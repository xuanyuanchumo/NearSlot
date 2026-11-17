/**
 * axios 统一请求层
 *
 * 对齐 docs/api/design.md 与 docs/standards/frontend.md §2：
 * 1. baseURL = VITE_API_BASE_URL（开发环境 /api，由 Vite 代理到 8081），timeout 15s；
 * 2. 请求拦截注入小写 authorization: Bearer <AT>，AT 为空则整条头不出现（禁止 Bearer null）；
 * 3. 响应拦截解包 { code, message, data, timestamp }，code !== 200 抛 ApiError；
 * 4. 401 按 X-Auth-Reason 分支：superseded（40102）单飞登出 + 跳登录；no-session（40101）跳登录；
 * 5. 刷新失败透传 X-Token-Refresh: failed，只提示一次，禁止无限重试；
 * 6. 42902 幂等拦截 -> 提示"请勿重复提交"；42901 限流 -> 按 Retry-After 提示；
 * 7. transformResponse 用 json-bigint(storeAsString) 防雪花 ID 精度丢失。
 */
import axios, {
  type AxiosError,
  type AxiosInstance,
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from "axios";
import { ElMessage } from "element-plus";
import { JSONBigInt } from "@/utils/json";
import {
  applyAuthSession,
  buildAuthHeaders,
  clearAuth,
  getMemoryRefreshToken,
  setMemoryRefreshToken,
} from "@/utils/auth";
import { gotoLogin } from "@/utils/navigation";
import type { ApiResult } from "@/types/api";

/* ------------------------------- 错误类型 ------------------------------- */

/** 业务错误：携带后端 code / message / data，便于页面按 code 分支 */
export class ApiError extends Error {
  readonly code: number;
  readonly httpStatus?: number;
  readonly data?: unknown;

  constructor(code: number, message: string, httpStatus?: number, data?: unknown) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.httpStatus = httpStatus;
    this.data = data;
  }
}

/** 业务码字典（api/design.md §3） */
export const BizCode = {
  SUCCESS: 200,
  UNAUTHORIZED: 40101,
  SUPERSEDED: 40102,
  RATE_LIMITED: 42901,
  DUPLICATE_SUBMIT: 42902,
  STOCK_INSUFFICIENT: 50101,
  DUPLICATE_APPOINTMENT: 50102,
  SLOT_CLOSED: 50103,
  LEVEL_NOT_ENOUGH: 50104,
  WAITLIST_FULL: 50105,
} as const;

/** 无需 token 也能访问的路径（登录、注册、店铺列表等公开只读接口） */
const PUBLIC_PATHS = ["/auth/login", "/auth/refresh", "/shops", "/health"];

function isPublicPath(url: string | undefined): boolean {
  if (!url) return false;
  const path = url.startsWith("http") ? new URL(url).pathname : url;
  return PUBLIC_PATHS.some((item) => path.startsWith(item));
}

/* ---------------------------- 单飞刷新（防刷新风暴） ---------------------------- */

interface RetriableConfig extends InternalAxiosRequestConfig {
  /** 该请求是否已经因 401 重放过，避免死循环 */
  __nearslotRetried?: boolean;
  /** 内部刷新请求标记，不参与 401 拦截 */
  __nearslotSkipAuth?: boolean;
}

/** 模块级单飞 promise：并发 401 只发一次刷新请求 */
let refreshPromise: Promise<string> | null = null;

/** 刷新失败标记：同一批并发请求只提示一次 */
let refreshFailureNotified = false;

/**
 * 执行 token 刷新。
 * RT 优先走 请求头 Refresh-Token（内存态），否则依赖后端 HttpOnly Cookie 兜底。
 * 成功返回新的 AT；失败抛 ApiError 并清理会话。
 */
async function doRefreshToken(): Promise<string> {
  const headers: Record<string, string> = {};
  const refreshToken = getMemoryRefreshToken();
  if (refreshToken) headers["Refresh-Token"] = refreshToken;

  const response = await axios.post<
    ApiResult<{ accessToken: string; userId?: string; level?: 0 | 1 | 2 | 3 }>
  >(`${apiBaseUrl}/auth/refresh`, null, {
    timeout: REQUEST_TIMEOUT,
    withCredentials: true, // RT Cookie 兜底
    headers,
    transformResponse: [(data) => (data ? JSONBigInt.parse(data as string) : data)],
    validateStatus: () => true,
  });

  const refreshState = response.headers["x-token-refresh"] ?? response.headers["X-Token-Refresh"];
  const body = response.data;

  if (
    refreshState === "failed" ||
    !body ||
    body.code !== BizCode.SUCCESS ||
    !body.data?.accessToken
  ) {
    throw new ApiError(
      body?.code ?? BizCode.UNAUTHORIZED,
      body?.message ?? "登录状态已失效，请重新登录",
    );
  }

  const newToken = body.data.accessToken;
  applyAuthSession(newToken, { userId: body.data.userId, level: body.data.level });
  setMemoryRefreshToken(null); // 刷新成功即清掉旧的内存 RT，等待下一次下发
  return newToken;
}

/** 单飞包装：同一时刻只有一个刷新在飞 */
function refreshTokenOnce(): Promise<string> {
  if (!refreshPromise) {
    refreshPromise = doRefreshToken().finally(() => {
      refreshPromise = null;
    });
  }
  return refreshPromise;
}

/* -------------------------------- 实例 -------------------------------- */

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || "/api";
const REQUEST_TIMEOUT = 15_000;

const service: AxiosInstance = axios.create({
  baseURL: apiBaseUrl,
  timeout: REQUEST_TIMEOUT,
  withCredentials: true, // 携带 HttpOnly Refresh Cookie
  headers: { "Content-Type": "application/json" },
  // 关键：用 json-bigint 解析响应，Long 一律成 string
  transformResponse: [
    (data: unknown) => {
      if (typeof data !== "string") return data;
      if (!data) return data;
      try {
        return JSONBigInt.parse(data);
      } catch {
        return data;
      }
    },
  ],
});

/* ------------------------------ 请求拦截器 ------------------------------ */

service.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const extended = config as RetriableConfig;
    if (extended.__nearslotSkipAuth) return config;

    const authHeaders = buildAuthHeaders();
    if (authHeaders.authorization) {
      config.headers.set("authorization", authHeaders.authorization);
    }
    // 请求头 RT 优先（内存态），Cookie 由浏览器自动携带
    if (authHeaders["Refresh-Token"]) {
      config.headers.set("Refresh-Token", authHeaders["Refresh-Token"]);
    }
    return config;
  },
  (error: unknown) => Promise.reject(error),
);

/* ------------------------------ 响应拦截器 ------------------------------ */

function readHeader(headers: unknown, name: string): string | undefined {
  if (!headers || typeof headers !== "object") return undefined;
  const record = headers as Record<string, unknown>;
  const direct = record[name] ?? record[name.toLowerCase()];
  return typeof direct === "string" ? direct : undefined;
}

service.interceptors.response.use(
  (response: AxiosResponse) => {
    const config = response.config as RetriableConfig;
    // 刷新接口与文件流不走统一解包
    if (config.__nearslotSkipAuth) return response;
    if (config.responseType === "blob" || config.responseType === "arraybuffer") return response;

    const body = response.data as ApiResult<unknown> | string | undefined;
    if (typeof body === "string" || body === undefined) return response;

    if (body && typeof body === "object" && "code" in body) {
      if (body.code === BizCode.SUCCESS) return body.data as never;

      // 业务失败：统一出口提示 + 抛出 ApiError，页面可按 code 分支
      const apiError = new ApiError(
        body.code,
        body.message || "请求失败",
        response.status,
        body.data,
      );
      notifyBizError(apiError, config);
      return Promise.reject(apiError);
    }

    return response;
  },
  async (error: unknown) => {
    const axiosError = error as AxiosError<ApiResult<unknown>>;
    const config = (axiosError.config ?? {}) as RetriableConfig;
    const status = axiosError.response?.status;
    const headers = axiosError.response?.headers;
    const reason = readHeader(headers, "X-Auth-Reason");
    const refreshState = readHeader(headers, "X-Token-Refresh");
    const body = axiosError.response?.data;
    const bizCode =
      typeof body === "object" && body && "code" in body ? Number(body.code) : undefined;

    // ------------------------------ 401 分支 ------------------------------
    if (status === 401) {
      // 刷新失败：只提示一次，禁止无限重试（api/design.md §4）
      if (refreshState === "failed") {
        if (!refreshFailureNotified) {
          refreshFailureNotified = true;
          ElMessage.error("登录状态已失效，请重新登录");
          window.setTimeout(() => {
            refreshFailureNotified = false;
          }, 3000);
        }
        clearAuth();
        await gotoLogin({ message: "登录状态已失效，请重新登录" });
        return Promise.reject(
          new ApiError(bizCode ?? BizCode.UNAUTHORIZED, "登录状态已失效，请重新登录", status),
        );
      }

      // 凭证被顶替：单飞登出 + 跳登录，不再尝试刷新
      if (reason === "superseded" || bizCode === BizCode.SUPERSEDED) {
        ElMessage.warning("账号已在其它设备登录，当前会话已失效");
        clearAuth();
        await gotoLogin({ message: "账号已在其它设备登录" });
        return Promise.reject(new ApiError(BizCode.SUPERSEDED, "账号已在其它设备登录", status));
      }

      // no-session：未登录或会话失效，直接跳登录并记录回跳
      if (reason === "no-session" || bizCode === BizCode.UNAUTHORIZED) {
        clearAuth();
        await gotoLogin({ message: "请先登录" });
        return Promise.reject(new ApiError(BizCode.UNAUTHORIZED, "请先登录", status));
      }

      // 其余 401：单飞刷新 + 重放原请求（每个请求最多重放一次）
      if (!config.__nearslotRetried && !isPublicPath(config.url)) {
        config.__nearslotRetried = true;
        try {
          const newToken = await refreshTokenOnce();
          config.headers.set("authorization", `Bearer ${newToken}`);
          return service.request(config as AxiosRequestConfig);
        } catch (refreshError) {
          clearAuth();
          if (!refreshFailureNotified) {
            refreshFailureNotified = true;
            ElMessage.error("登录状态已失效，请重新登录");
            window.setTimeout(() => {
              refreshFailureNotified = false;
            }, 3000);
          }
          await gotoLogin({ message: "登录状态已失效，请重新登录" });
          return Promise.reject(
            refreshError instanceof ApiError
              ? refreshError
              : new ApiError(BizCode.UNAUTHORIZED, "登录状态已失效，请重新登录", status),
          );
        }
      }

      clearAuth();
      await gotoLogin({ message: "请先登录" });
      return Promise.reject(new ApiError(BizCode.UNAUTHORIZED, "请先登录", status));
    }

    // ------------------------ 限流 / 幂等 / 其它业务码 ------------------------
    if (
      status === 429 ||
      bizCode === BizCode.RATE_LIMITED ||
      bizCode === BizCode.DUPLICATE_SUBMIT
    ) {
      const code = bizCode ?? BizCode.RATE_LIMITED;
      if (code === BizCode.DUPLICATE_SUBMIT) {
        ElMessage.warning("请勿重复提交");
        return Promise.reject(new ApiError(BizCode.DUPLICATE_SUBMIT, "请勿重复提交", status));
      }
      const retryAfter = readHeader(headers, "Retry-After");
      const message =
        typeof body === "object" && body?.message ? body.message : "操作过于频繁，请稍后再试";
      ElMessage.warning(retryAfter ? `${message}（${retryAfter}s 后重试）` : message);
      return Promise.reject(new ApiError(BizCode.RATE_LIMITED, message, status));
    }

    // 其它 HTTP 错误
    const message =
      (typeof body === "object" && body?.message) ||
      (axiosError.code === "ECONNABORTED" ? "请求超时，请稍后重试" : "") ||
      axiosError.message ||
      "网络异常，请稍后重试";
    ElMessage.error(message);
    return Promise.reject(new ApiError(bizCode ?? status ?? 500, message, status, body));
  },
);

/** 业务错误统一提示出口（可按 code 定制文案） */
function notifyBizError(error: ApiError, _config?: RetriableConfig): void {
  switch (error.code) {
    case BizCode.STOCK_INSUFFICIENT:
      ElMessage.error("该时段余量不足，请选择其它时段");
      break;
    case BizCode.DUPLICATE_APPOINTMENT:
      ElMessage.warning("您已预约该时段，请勿重复预约");
      break;
    case BizCode.SLOT_CLOSED:
      ElMessage.warning("该时段已满或已关闭");
      break;
    case BizCode.LEVEL_NOT_ENOUGH:
      ElMessage.warning("当前会员等级不满足该时段预约要求");
      break;
    case BizCode.WAITLIST_FULL:
      ElMessage.warning("候补队列已满，请稍后再试");
      break;
    case BizCode.DUPLICATE_SUBMIT:
      ElMessage.warning("请勿重复提交");
      break;
    default:
      ElMessage.error(error.message || "请求失败");
  }
}

/* ------------------------------ 对外 API ------------------------------ */

/** 统一请求方法：返回值即后端 data，类型由调用方指定 */
async function request<T>(config: AxiosRequestConfig): Promise<T> {
  return (await service.request(config)) as unknown as T;
}

export const http = {
  get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return request<T>({ ...config, url, method: "GET" });
  },
  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return request<T>({ ...config, url, method: "POST", data });
  },
  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return request<T>({ ...config, url, method: "PUT", data });
  },
  patch<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return request<T>({ ...config, url, method: "PATCH", data });
  },
  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return request<T>({ ...config, url, method: "DELETE" });
  },
};

export { service, JSONBigInt, apiBaseUrl, REQUEST_TIMEOUT };
export default http;
