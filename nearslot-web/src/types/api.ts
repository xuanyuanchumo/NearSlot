/**
 * NearSlot 前端 API 类型定义
 *
 * 精度铁律：所有后端 Long / 雪花 ID 一律用 string 承载。
 * 后端 jackson WRITE_NUMBERS_AS_STRINGS + 前端 json-bigint(storeAsString) 双保险，
 * 任何一层缺失都会导致 ID 精度丢失（见 docs/standards/frontend.md §2、ADR-012）。
 */

/** 统一响应体：{ code, message, data, timestamp } */
export interface ApiResult<T> {
  code: number;
  message: string;
  data: T;
  timestamp: number;
}

/** 游标分页结果（api/design.md §6：默认 cursor + limit=20） */
export interface PageResult<T> {
  /** 当前页数据 */
  items: T[];
  /** 下一页游标；为空表示没有更多 */
  cursor?: string | null;
  /** 是否还有下一页 */
  hasMore?: boolean;
  /** 服务端总数（可选，深分页场景可能不返回） */
  total?: number;
}

/** 会员等级（api/design.md：枚举传输用 code，文案由前端字典翻译） */
export type UserLevel = 0 | 1 | 2 | 3;

export const USER_LEVEL_TEXT: Record<UserLevel, string> = {
  0: "普通用户",
  1: "铜牌会员",
  2: "银牌会员",
  3: "金牌会员",
};

/** 登录请求体 */
export interface LoginPayload {
  username: string;
  password: string;
}

/** 登录响应体（RT 由后端写入 HttpOnly Cookie，前端永不落地） */
export interface LoginResult {
  accessToken: string;
  userId: string;
  level: UserLevel;
  /** AT 有效期（秒），仅用于展示 */
  expiresIn?: number;
}

/** 服务商（店铺） */
export interface Shop {
  id: string;
  name: string;
  coverUrl?: string;
  description?: string;
  address?: string;
  /** 与服务商距离（米），由后端 GEOSEARCH 下发 */
  distanceMeters?: number;
  /** 评分 0-5，保留一位小数 */
  score?: number;
  /** 业务类型 id（服务类目） */
  typeId?: number;
  /** 起价（分），金额一律用整数分避免浮点误差 */
  minPriceCents?: number;
}

/** 技师 */
export interface Technician {
  id: string;
  shopId: string;
  name: string;
  avatarUrl?: string;
  title?: string;
  score?: number;
}

/** 时段状态机（全局唯一枚举，禁止散落魔法数字） */
export type SlotStatus = "available" | "held" | "booked" | "disabled" | "past";

export const SLOT_STATUS_TEXT: Record<SlotStatus, string> = {
  available: "可预约",
  held: "已被锁定",
  booked: "已预约",
  disabled: "不可预约",
  past: "已过期",
};

/** 时段槽位（稀缺资源，状态以服务端返回为准，前端禁止本地占位） */
export interface TimeSlot {
  id: string;
  shopId: string;
  serviceStaffId?: string;
  /** UTC 瞬时（ISO-8601），展示时必须配合 tzId 渲染墙钟时间 */
  startsAt: string;
  /** IANA 时区，如 Asia/Shanghai；禁止用浏览器本地时区替代 */
  tzId: string;
  durationMinutes: number;
  /** 价格（分） */
  priceCents: number;
  status: SlotStatus;
  /** 乐观锁版本号，服务端状态回查用 */
  version?: number;
  /** 不可预约原因（用于 tooltip 说明） */
  reason?: string;
}

/** 预约单状态 */
export type AppointmentStatus =
  | "PENDING"
  | "HELD"
  | "CONFIRMED"
  | "CANCELLED"
  | "EXPIRED"
  | "COMPLETED"
  | "NO_SHOW";

export const APPOINTMENT_STATUS_TEXT: Record<AppointmentStatus, string> = {
  PENDING: "待确认",
  HELD: "已锁定",
  CONFIRMED: "已预约",
  CANCELLED: "已取消",
  EXPIRED: "已过期",
  COMPLETED: "已完成",
  NO_SHOW: "未到店",
};

/** 预约单 */
export interface Appointment {
  id: string;
  slotId: string;
  shopId: string;
  shopName?: string;
  serviceStaffId?: string;
  technicianName?: string;
  userId: string;
  status: AppointmentStatus;
  /** 预约时段开始时间（UTC ISO + tzId） */
  startsAt: string;
  tzId: string;
  durationMinutes?: number;
  amountCents?: number;
  /** Hold 过期时间（ISO），倒计时以服务端时间为准 */
  holdExpiresAt?: string;
  createdAt?: string;
}

/** 抢约结果：hold 两段式预约的第一段 */
export interface GrabResult {
  appointmentId: string;
  holdId: string;
  /** Hold 过期时间（ISO） */
  expiresAt: string;
  /** 服务端当前时间（ISO），用于校准客户端时钟偏移 */
  serverTime?: string;
}

/* ------------------------------ AI Agent SSE ------------------------------ */

/** 工具执行状态（SSE 规范 §5） */
export type AgentTaskStatus = "RUNNING" | "COMPLETED" | "FAILED" | "SKIPPED";

/** meta：会话建立，必须是首个事件 */
export interface AgentMetaEvent {
  type: "meta";
  conversationId: string;
  round?: number;
  timestamp?: number;
}

/** 规划任务项 */
export interface AgentPlanTask {
  id: string;
  description: string;
  toolName?: string;
  taskType?: string;
  status: AgentTaskStatus;
}

/** plan：每轮全量替换任务清单 */
export interface AgentPlanEvent {
  type: "plan";
  round: number;
  tasks: AgentPlanTask[];
}

/** progress：阶段或单步进度 */
export interface AgentProgressEvent {
  type: "progress";
  stage?: "planning" | "executing" | "merging";
  step?: {
    taskId?: string;
    toolName?: string;
    status: AgentTaskStatus;
  };
  message?: string;
}

/** error：可恢复错误，服务端随后结束流 */
export interface AgentErrorEvent {
  type: "error";
  code: number | string;
  error: string;
}

/** confirm：高风险工具需人工审批，确认后 POST /agent/conversations/{id}/confirm 续流 */
export interface AgentConfirmEvent {
  type: "confirm";
  confirmId: string;
  tool: string;
  reason?: string;
  arguments?: Record<string, unknown>;
}

/** 普通文本增量（token 追加） */
export interface AgentTextEvent {
  type: "text";
  text: string;
}

export type AgentEvent =
  | AgentMetaEvent
  | AgentPlanEvent
  | AgentProgressEvent
  | AgentErrorEvent
  | AgentConfirmEvent
  | AgentTextEvent;
