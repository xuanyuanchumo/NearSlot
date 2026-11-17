/** 预约接口：抢约 / 列表 / 取消 */
import http from "@/utils/request";
import type { Appointment, GrabResult, PageResult } from "@/types/api";

export interface GrabPayload {
  slotId: string;
  /** 幂等键：同一业务键在 TTL 内重复提交返回 42902 */
  idempotencyKey?: string;
}

/**
 * 抢约（hold 两段式第一段）。
 * 后端 Lua 三检查（余量 / 一人一时段 / 等级），前端不做本地占位。
 */
export function grabAppointment(payload: GrabPayload): Promise<GrabResult> {
  return http.post<GrabResult>("/appointments/grab", payload, {
    headers: payload.idempotencyKey ? { "Idempotency-Key": payload.idempotencyKey } : undefined,
  });
}

export interface AppointmentQuery {
  cursor?: string;
  limit?: number;
  status?: string;
}

/** 我的预约列表 */
export function fetchAppointments(query: AppointmentQuery = {}): Promise<PageResult<Appointment>> {
  return http.get<PageResult<Appointment>>("/appointments", { params: query });
}

/** 预约详情 */
export function fetchAppointment(id: string): Promise<Appointment> {
  return http.get<Appointment>(`/appointments/${id}`);
}

/** 取消预约（状态机校验 CONFIRMED -> CANCELLED） */
export function cancelAppointment(id: string): Promise<void> {
  return http.post<void>(`/appointments/${id}/cancel`);
}
