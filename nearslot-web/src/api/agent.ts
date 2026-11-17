/** AI 助手接口（SSE 流式对话在 utils/sse.ts 中处理） */
import http from "@/utils/request";

export interface ConfirmDecisionPayload {
  conversationId: string;
  confirmId: string;
  /** true = 批准执行，false = 拒绝 */
  approved: boolean;
  reason?: string;
}

/**
 * 审批决策：confirm 事件暂停后调用，随后需重新建立 SSE 续流。
 */
export function submitConfirmDecision(payload: ConfirmDecisionPayload): Promise<void> {
  return http.post<void>(`/agent/conversations/${payload.conversationId}/confirm`, {
    confirmId: payload.confirmId,
    approved: payload.approved,
    reason: payload.reason,
  });
}

/** 结束会话（可选清理） */
export function closeConversation(conversationId: string): Promise<void> {
  return http.delete<void>(`/agent/conversations/${conversationId}`);
}
