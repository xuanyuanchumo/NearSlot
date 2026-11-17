/**
 * SSE 客户端（AI 助手流式对话）
 *
 * 为什么不用 EventSource：
 * 浏览器原生 EventSource 无法自定义 Authorization 请求头，也无法 POST body，
 * 因此改用 fetch + ReadableStream 手工解析 SSE 帧（docs/research/11-frontend-ux-deep.md §5.2）。
 *
 * 契约（docs/api/design.md §7）：
 * - 事件类型：meta / plan / progress / error / confirm，以及普通文本增量；
 * - meta 必须为首事件，用于保存 conversationId；
 * - plan 每轮全量替换任务清单；
 * - confirm 表示暂停等待人工审批，需 POST confirm 接口续流；
 * - EOF（reader.done）即结束，兼容历史 DONE 事件但服务端不再发送；
 * - 断线重连带 Last-Event-ID 增量补发，最多 3 次指数退避（1s / 2s / 4s）。
 */
import { buildAuthHeaders } from "@/utils/auth";
import { tryParseJsonBig } from "@/utils/json";
import { apiBaseUrl } from "@/utils/request";
import type { AgentEvent } from "@/types/api";

/* ------------------------------- 类型 ------------------------------- */

export type SseStatus =
  | "idle"
  | "connecting"
  | "streaming"
  | "awaiting_confirm"
  | "reconnecting"
  | "done"
  | "error";

export interface SseHandlers {
  /** 任意已解析事件（已按 type 归一化） */
  onEvent?: (event: AgentEvent) => void;
  /** 连接建立（收到响应头） */
  onOpen?: () => void;
  /** 普通文本增量 */
  onText?: (text: string) => void;
  /** 连接建立并收到 meta 后回调 conversationId */
  onConversation?: (conversationId: string) => void;
  /** 可恢复错误事件（来自 error 事件或网络层） */
  onError?: (error: SseClientError) => void;
  /** 状态变化，便于 UI 展示"重连中"等 */
  onStatus?: (status: SseStatus) => void;
  /** 流正常结束 */
  onDone?: () => void;
}

export interface SseClientError {
  code: number | string;
  message: string;
  /** 是否仍可重连 */
  retryable: boolean;
}

export interface SseSendOptions {
  /** 后端接口路径，如 /agent/conversations/chat */
  url: string;
  /** 请求体（POST JSON） */
  body?: unknown;
  /** 显式指定会话 id（续流时带上） */
  conversationId?: string;
  /** 断线重连时补发的 Last-Event-ID */
  lastEventId?: string;
  handlers?: SseHandlers;
  signal?: AbortSignal;
}

/* ------------------------------- 实现 ------------------------------- */

/** 最多 3 次重连退避：1s / 2s / 4s */
const BACKOFF_DELAYS = [1000, 2000, 4000] as const;
const MAX_RETRIES = BACKOFF_DELAYS.length;

/** 单条事件帧解析结果 */
interface RawSseFrame {
  id?: string;
  event?: string;
  data: string;
}

function delay(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new DOMException("Aborted", "AbortError"));
      return;
    }
    const timer = window.setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    function onAbort(): void {
      window.clearTimeout(timer);
      reject(new DOMException("Aborted", "AbortError"));
    }
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}

/** 把 SSE 文本缓冲按空行拆帧；返回完整帧与剩余未完成缓冲 */
function splitFrames(buffer: string): { frames: string[]; rest: string } {
  const normalized = buffer.replace(/\r\n/g, "\n");
  const parts = normalized.split("\n\n");
  const rest = parts.pop() ?? "";
  return { frames: parts.filter((part) => part.trim().length > 0), rest };
}

/** 解析单帧：支持 id / event / data 多行 data 拼接 */
function parseFrame(raw: string): RawSseFrame | null {
  const lines = raw.split("\n");
  let id: string | undefined;
  let event: string | undefined;
  const dataLines: string[] = [];
  for (const line of lines) {
    if (!line || line.startsWith(":")) continue; // 冒号开头是注释/心跳
    const colon = line.indexOf(":");
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    switch (field) {
      case "id":
        id = value;
        break;
      case "event":
        event = value;
        break;
      case "data":
        dataLines.push(value);
        break;
      default:
        break;
    }
  }
  if (dataLines.length === 0) return null;
  return { id, event, data: dataLines.join("\n") };
}

/** 把原始帧归一化为 AgentEvent；无法识别时返回 null */
function normalizeEvent(frame: RawSseFrame): AgentEvent | null {
  const raw = frame.data.trim();
  if (!raw) return null;
  if (raw === "[DONE]" || raw === "DONE") return null;

  // 用 json-bigint 解析，事件里的 Long（如 confirmId 关联的 id）同样不丢精度
  const parsed: unknown = tryParseJsonBig<unknown>(raw);
  if (!parsed || typeof parsed !== "object") return null;

  const record = parsed as Record<string, unknown>;
  const type = (record.type ?? frame.event ?? "").toString();

  switch (type) {
    case "meta":
      return {
        type: "meta",
        conversationId: String(record.conversationId ?? ""),
        round: typeof record.round === "number" ? record.round : undefined,
        timestamp: typeof record.timestamp === "number" ? record.timestamp : undefined,
      };
    case "plan":
      return {
        type: "plan",
        round: typeof record.round === "number" ? record.round : 1,
        tasks: Array.isArray(record.tasks) ? (record.tasks as never[]) : [],
      };
    case "progress":
      return {
        type: "progress",
        stage: record.stage as never,
        step: record.step as never,
        message: typeof record.message === "string" ? record.message : undefined,
      };
    case "error":
      return {
        type: "error",
        code: (record.code as number | string) ?? "SSE_ERROR",
        error: String(record.error ?? record.message ?? "AI 助手执行失败"),
      };
    case "confirm":
      return {
        type: "confirm",
        confirmId: String(record.confirmId ?? ""),
        tool: String(record.tool ?? ""),
        reason: typeof record.reason === "string" ? record.reason : undefined,
        arguments: (record.arguments as Record<string, unknown>) ?? {},
      };
    case "text":
      return { type: "text", text: String(record.text ?? record.data ?? "") };
    default:
      // 未声明 type 但带 text 字段：按文本处理
      if (typeof record.text === "string") return { type: "text", text: record.text };
      return null;
  }
}

/**
 * SSE 客户端：一次 send 管理完整生命周期（连接 / 重连 / 中止）。
 * 返回 abort 句柄，UI 的"停止生成"按钮直接调用。
 */
export class SseClient {
  private controller: AbortController | null = null;

  private retries = 0;

  private lastEventId = "";

  private conversationId = "";

  private finished = false;

  private currentStatus: SseStatus = "idle";

  get status(): SseStatus {
    return this.currentStatus;
  }

  get conversation(): string {
    return this.conversationId;
  }

  /** 主动中止流（不触发重连） */
  abort(): void {
    this.finished = true;
    this.controller?.abort();
    this.setStatus("done");
  }

  private setStatus(status: SseStatus): void {
    this.currentStatus = status;
    this.handlers?.onStatus?.(status);
  }

  private handlers: SseHandlers = {};

  /**
   * 建立 SSE 连接并消费事件流。
   * 网络异常或非 2xx 会按 1s/2s/4s 退避重连，最多 3 次；error 事件不重连。
   */
  async send(options: SseSendOptions): Promise<void> {
    this.handlers = options.handlers ?? {};
    this.finished = false;
    this.retries = 0;
    this.lastEventId = options.lastEventId ?? "";
    this.conversationId = options.conversationId ?? "";

    await this.attempt(options);
  }

  private async attempt(options: SseSendOptions): Promise<void> {
    if (this.finished) return;
    this.controller = new AbortController();
    const signal = this.controller.signal;
    const outerSignal = options.signal;
    const onOuterAbort = (): void => this.abort();
    outerSignal?.addEventListener("abort", onOuterAbort, { once: true });

    try {
      this.setStatus(this.retries === 0 ? "connecting" : "reconnecting");

      const headers: Record<string, string> = {
        "Content-Type": "application/json",
        Accept: "text/event-stream",
        ...buildAuthHeaders(),
      };
      if (this.lastEventId) headers["Last-Event-ID"] = this.lastEventId;
      if (this.conversationId) headers["X-Conversation-Id"] = this.conversationId;

      const body: Record<string, unknown> = {
        ...(options.body as Record<string, unknown> | undefined),
      };
      if (this.conversationId) body.conversationId = this.conversationId;

      const response = await fetch(`${apiBaseUrl}${options.url}`, {
        method: "POST",
        headers,
        credentials: "include", // RT Cookie 兜底
        body: JSON.stringify(body),
        signal,
      });

      if (!response.ok) {
        const authReason = response.headers.get("X-Auth-Reason") ?? "";
        const message =
          response.status === 401
            ? authReason === "superseded"
              ? "账号已在其它设备登录，请重新登录"
              : "登录状态已失效，请重新登录"
            : `连接失败（HTTP ${response.status}）`;
        // 4xx 不重连，5xx 允许退避重试
        const retryable = response.status >= 500;
        throw Object.assign(new Error(message), { code: response.status, retryable });
      }

      if (!response.body) {
        throw Object.assign(new Error("当前浏览器不支持流式响应"), {
          code: "NO_STREAM",
          retryable: false,
        });
      }

      this.handlers.onOpen?.();
      this.setStatus("streaming");

      const reader = response.body.getReader();
      const decoder = new TextDecoder("utf-8");
      let buffer = "";

      // eslint-disable-next-line no-constant-condition
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const { frames, rest } = splitFrames(buffer);
        buffer = rest;
        for (const frameText of frames) {
          const frame = parseFrame(frameText);
          if (!frame) continue;
          if (frame.id) this.lastEventId = frame.id;
          this.dispatch(frame);
          if (this.finished) {
            await reader.cancel().catch(() => undefined);
            return;
          }
        }
      }

      // 收尾：处理没有以空行结束的最后一帧
      if (buffer.trim().length > 0) {
        const frame = parseFrame(buffer);
        if (frame) {
          if (frame.id) this.lastEventId = frame.id;
          this.dispatch(frame);
        }
      }

      if (this.finished) return;
      if (this.currentStatus !== "error") {
        this.setStatus("done");
        this.handlers.onDone?.();
      }
    } catch (error) {
      if (this.finished || (error instanceof DOMException && error.name === "AbortError")) {
        return;
      }
      const err = error as { code?: number | string; message?: string; retryable?: boolean };
      const retryable = err.retryable !== false;

      if (retryable && this.retries < MAX_RETRIES) {
        const wait = BACKOFF_DELAYS[this.retries] ?? BACKOFF_DELAYS[MAX_RETRIES - 1];
        this.retries += 1;
        this.setStatus("reconnecting");
        try {
          await delay(wait, signal);
        } catch {
          return;
        }
        if (this.finished) return;
        await this.attempt(options);
        return;
      }

      this.setStatus("error");
      this.handlers.onError?.({
        code: err.code ?? "NETWORK_ERROR",
        message: err.message ?? "网络异常，连接已中断",
        retryable: false,
      });
    } finally {
      outerSignal?.removeEventListener("abort", onOuterAbort);
    }
  }

  /** 分发单个已解析事件 */
  private dispatch(frame: RawSseFrame): void {
    const event = normalizeEvent(frame);
    if (!event) return;

    if (event.type === "meta") {
      this.conversationId = event.conversationId;
      this.handlers.onConversation?.(event.conversationId);
    } else if (event.type === "confirm") {
      this.setStatus("awaiting_confirm");
    } else if (event.type === "error") {
      this.setStatus("error");
      this.handlers.onError?.({ code: event.code, message: event.error, retryable: false });
      this.finished = true;
    } else if (event.type === "text") {
      if (event.text) this.handlers.onText?.(event.text);
    }

    this.handlers.onEvent?.(event);
  }
}

/** 便捷方法：创建客户端并发起一次 SSE 会话 */
export function createSseClient(): SseClient {
  return new SseClient();
}

export { MAX_RETRIES, BACKOFF_DELAYS };
