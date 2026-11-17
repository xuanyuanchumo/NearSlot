/** 预约接口：抢约 / 列表 / 取消（契约以 docs/api/design.md §7.5 为准） */
import http from "@/utils/request";
import type { Appointment } from "@/types/api";

export interface GrabPayload {
  slotId: string;
}

/**
 * 抢约（Hold 两段式第一段）。
 *
 * 2026-11-17 审计修复（Blocker）：后端是 POST /api/v1/appointments（返回 orderId:Long），
 * 不是 /appointments/grab（实测 405）。
 * 幂等由后端 @Idempotent("#userId + ':' + #slotId", ttl=10) 保证，前端无需传幂等键。
 */
export function grabAppointment(payload: GrabPayload): Promise<string> {
  return http.post<string>("/appointments", payload);
}

/**
 * 我的预约列表。
 *
 * 2026-11-17 审计修复（Blocker）：后端返回裸数组（不是 PageResult），
 * 前端按 page.items 取会得到 undefined，列表恒空。
 */
export function fetchAppointments(status?: number): Promise<Appointment[]> {
  return http.get<Appointment[]>("/appointments", {
    params: status === undefined ? {} : { status },
  });
}

/** 预约详情（归属校验在后端） */
export function fetchAppointment(id: string): Promise<Appointment> {
  return http.get<Appointment>(`/appointments/${id}`);
}

/** 取消预约（后端状态机：仅 PENDING/CONFIRMED 可取消） */
export function cancelAppointment(id: string): Promise<void> {
  return http.post<void>(`/appointments/${id}/cancel`);
}

/** 加入候补（返回位次；已存在返回原序位） */
export function joinWaitlist(slotId: string): Promise<number> {
  return http.post<number>(`/appointments/${slotId}/waitlist`);
}

/** 退出候补 */
export function leaveWaitlist(slotId: string): Promise<void> {
  return http.delete<void>(`/appointments/${slotId}/waitlist`);
}
