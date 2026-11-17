<script setup lang="ts">
/**
 * AI 助手对话页：SSE 流式回答 + 计划清单 + 高风险工具确认卡
 *
 * 契约（docs/api/design.md §7、docs/research/11-frontend-ux-deep.md §5）：
 * - meta 首事件保存 conversationId；
 * - plan 每轮全量替换任务清单，progress 按 taskId / toolName 更新状态；
 * - confirm 暂停等待人工审批，用户决策后 POST confirm 并以 conversationId 续流；
 * - error 事件展示错误并结束流；EOF 即结束（兼容历史 DONE 但不依赖）；
 * - 断线由 SseClient 自动退避重连（1s/2s/4s，最多 3 次），带 Last-Event-ID。
 */
import { computed, nextTick, onBeforeUnmount, ref, shallowRef } from "vue";
import { ElMessage } from "element-plus";
import { submitConfirmDecision } from "@/api/agent";
import { createSseClient, type SseClient, type SseStatus } from "@/utils/sse";
import type { AgentConfirmEvent, AgentEvent, AgentPlanTask, AgentTaskStatus } from "@/types/api";

interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  streaming: boolean;
}

const messages = ref<ChatMessage[]>([]);
const input = ref("");
const status = ref<SseStatus>("idle");
const stageText = ref("");
const tasks = ref<AgentPlanTask[]>([]);
const confirmEvent = ref<AgentConfirmEvent | null>(null);
const confirmLoading = ref(false);
const errorMessage = ref("");
const conversationId = ref("");
const answerRegion = ref<HTMLElement | null>(null);

const client = shallowRef<SseClient | null>(null);
let messageSeed = 0;
let activeAssistantIndex = -1;

const streaming = computed(
  () =>
    status.value === "connecting" ||
    status.value === "streaming" ||
    status.value === "reconnecting",
);
const canSend = computed(
  () => input.value.trim().length > 0 && !streaming.value && !confirmEvent.value,
);

/** 工具状态 -> 图标/文案 */
const TASK_STATUS_META: Record<
  AgentTaskStatus,
  { text: string; type: "success" | "warning" | "danger" | "info" }
> = {
  RUNNING: { text: "执行中", type: "warning" },
  COMPLETED: { text: "已完成", type: "success" },
  FAILED: { text: "失败", type: "danger" },
  SKIPPED: { text: "跳过", type: "info" },
};

function taskMeta(statusValue: AgentTaskStatus) {
  return TASK_STATUS_META[statusValue] ?? TASK_STATUS_META.RUNNING;
}

function nextId(prefix: string): string {
  messageSeed += 1;
  return `${prefix}-${messageSeed}`;
}

async function scrollToBottom(): Promise<void> {
  await nextTick();
  answerRegion.value?.scrollTo({ top: answerRegion.value.scrollHeight, behavior: "smooth" });
}

/** 处理单个 SSE 事件 */
function handleEvent(event: AgentEvent): void {
  switch (event.type) {
    case "meta":
      conversationId.value = event.conversationId;
      stageText.value = "会话已建立";
      break;
    case "plan":
      // 每轮全量替换
      tasks.value = event.tasks.map((task) => ({ ...task }));
      stageText.value = `第 ${event.round} 轮规划完成，共 ${event.tasks.length} 个步骤`;
      break;
    case "progress": {
      if (event.stage) stageText.value = `阶段：${event.stage}`;
      const step = event.step;
      if (step) {
        const index = tasks.value.findIndex(
          (task) =>
            (step.taskId && task.id === step.taskId) ||
            (step.toolName && task.toolName === step.toolName),
        );
        if (index >= 0 && step.status) {
          tasks.value[index] = { ...tasks.value[index], status: step.status };
        }
      }
      if (event.message) stageText.value = event.message;
      break;
    }
    case "confirm":
      confirmEvent.value = event;
      stageText.value = "等待确认：该操作需要您的授权";
      break;
    case "error":
      errorMessage.value = `[${event.code}] ${event.error}`;
      stageText.value = "执行失败";
      break;
    case "text":
      if (activeAssistantIndex >= 0 && messages.value[activeAssistantIndex]) {
        messages.value[activeAssistantIndex].content += event.text;
        void scrollToBottom();
      }
      break;
    default:
      break;
  }
}

/** 发起一次 SSE 会话（续流时传 conversationId） */
function startStream(payload: Record<string, unknown>, resumeConversationId = ""): void {
  const current = createSseClient();
  client.value = current;

  // 新建助手消息气泡
  const assistantMessage: ChatMessage = {
    id: nextId("assistant"),
    role: "assistant",
    content: "",
    streaming: true,
  };
  messages.value.push(assistantMessage);
  activeAssistantIndex = messages.value.length - 1;
  void scrollToBottom();

  void current.send({
    url: "/agent/conversations/chat",
    body: payload,
    conversationId: resumeConversationId,
    handlers: {
      onEvent: handleEvent,
      onConversation: (id) => {
        conversationId.value = id;
      },
      onText: () => {
        // 文本增量已在 handleEvent 中处理，这里只负责滚动
        void scrollToBottom();
      },
      onStatus: (next) => {
        status.value = next;
        if (next === "done" || next === "error") {
          const target = messages.value[activeAssistantIndex];
          if (target) target.streaming = false;
        }
      },
      onError: (error) => {
        errorMessage.value = `[${error.code}] ${error.message}`;
        const target = messages.value[activeAssistantIndex];
        if (target) target.streaming = false;
      },
      onDone: () => {
        const target = messages.value[activeAssistantIndex];
        if (target) target.streaming = false;
        stageText.value = confirmEvent.value ? stageText.value : "回答已完成";
      },
    },
  });
}

function handleSend(): void {
  const text = input.value.trim();
  if (!text || streaming.value || confirmEvent.value) return;

  errorMessage.value = "";
  tasks.value = [];
  stageText.value = "";
  messages.value.push({ id: nextId("user"), role: "user", content: text, streaming: false });
  input.value = "";

  startStream({ message: text });
}

/** 停止生成（AbortController） */
function handleStop(): void {
  client.value?.abort();
  const target = messages.value[activeAssistantIndex];
  if (target) target.streaming = false;
  status.value = "done";
  stageText.value = "已停止生成";
}

/** 审批决策：POST confirm 后以 conversationId 续流 */
async function handleConfirm(approved: boolean): Promise<void> {
  const pending = confirmEvent.value;
  if (!pending || confirmLoading.value) return;

  confirmLoading.value = true;
  const targetConversation = conversationId.value;
  try {
    await submitConfirmDecision({
      conversationId: targetConversation,
      confirmId: pending.confirmId,
      approved,
      reason: approved ? "用户确认执行" : "用户拒绝执行",
    });
    confirmEvent.value = null;
    ElMessage.success(approved ? "已确认，继续执行" : "已拒绝该操作");
    // confirm 会结束当前 SSE，续流需要重新建立会话
    startStream({ approved }, targetConversation);
  } catch {
    // 错误提示已在拦截器统一出口处理
  } finally {
    confirmLoading.value = false;
  }
}

function resetConversation(): void {
  client.value?.abort();
  messages.value = [];
  tasks.value = [];
  confirmEvent.value = null;
  errorMessage.value = "";
  stageText.value = "";
  conversationId.value = "";
  status.value = "idle";
  activeAssistantIndex = -1;
}

onBeforeUnmount(() => {
  client.value?.abort();
});
</script>

<template>
  <div class="ns-page ns-agent">
    <header class="ns-header">
      <div>
        <h1 class="ns-title">AI 助手</h1>
        <p class="ns-text-muted">用自然语言预约时段，高风险操作会先请求确认</p>
      </div>
      <div class="ns-row">
        <el-button text @click="$router.push('/')">去逛逛</el-button>
        <el-button text :disabled="messages.length === 0" @click="resetConversation"
          >新会话</el-button
        >
      </div>
    </header>

    <!-- 错误：role=alert 立即播报 -->
    <el-alert
      v-if="errorMessage"
      class="ns-agent-error"
      type="error"
      role="alert"
      show-icon
      :closable="true"
      :title="errorMessage"
      @close="errorMessage = ''"
    />

    <!-- 计划清单：语义 ol，状态变化可被读出 -->
    <section v-if="tasks.length > 0" class="ns-card ns-plan" aria-live="polite">
      <header class="ns-plan-header">
        <h2 class="ns-plan-title">执行计划</h2>
        <span class="ns-text-muted">{{ stageText }}</span>
      </header>
      <ol class="ns-plan-list">
        <li v-for="task in tasks" :key="task.id" class="ns-plan-item">
          <span class="ns-plan-desc">{{ task.description }}</span>
          <el-tag :type="taskMeta(task.status).type" size="small" disable-transitions>
            {{ taskMeta(task.status).text }}
          </el-tag>
        </li>
      </ol>
    </section>

    <!-- 对话区 -->
    <section ref="answerRegion" class="ns-card ns-messages" aria-label="对话内容">
      <div v-if="messages.length === 0" class="ns-messages-empty">
        <el-empty description="试试问我：明天下午附近有没有可预约的技师？" :image-size="80" />
      </div>

      <div
        v-for="message in messages"
        :key="message.id"
        class="ns-message"
        :class="`is-${message.role}`"
      >
        <div class="ns-message-bubble" :aria-live="message.role === 'assistant' ? 'polite' : 'off'">
          <template v-if="message.content">{{ message.content }}</template>
          <span v-else-if="message.streaming" class="ns-text-muted">思考中…</span>
        </div>
      </div>

      <div v-if="streaming" class="ns-message is-assistant">
        <div class="ns-message-bubble ns-text-muted">
          <el-icon class="is-loading"><Loading /></el-icon>
          {{ status === "reconnecting" ? "连接中断，正在重连…" : "正在生成…" }}
        </div>
      </div>
    </section>

    <!-- confirm 确认卡：role=alertdialog，键盘可达 -->
    <section
      v-if="confirmEvent"
      class="ns-card ns-confirm"
      role="alertdialog"
      aria-modal="false"
      aria-labelledby="ns-confirm-title"
      aria-describedby="ns-confirm-desc"
    >
      <header class="ns-confirm-header">
        <el-icon color="var(--ns-warning)"><WarningFilled /></el-icon>
        <h2 id="ns-confirm-title" class="ns-plan-title">需要您确认的操作</h2>
      </header>
      <p id="ns-confirm-desc" class="ns-confirm-desc">
        工具 <strong>{{ confirmEvent.tool }}</strong>
        <span v-if="confirmEvent.reason">：{{ confirmEvent.reason }}</span>
      </p>
      <pre v-if="confirmEvent.arguments" class="ns-confirm-args">{{
        JSON.stringify(confirmEvent.arguments, null, 2)
      }}</pre>
      <div class="ns-row ns-confirm-actions">
        <el-button type="primary" :loading="confirmLoading" @click="handleConfirm(true)">
          <el-icon><CircleCheck /></el-icon>
          <span>确认执行</span>
        </el-button>
        <el-button :disabled="confirmLoading" @click="handleConfirm(false)">
          <el-icon><CircleClose /></el-icon>
          <span>拒绝</span>
        </el-button>
      </div>
    </section>

    <!-- 输入区 -->
    <footer class="ns-composer">
      <el-input
        v-model="input"
        type="textarea"
        :rows="2"
        resize="none"
        placeholder="描述你想要的时段，例如：明天下午 3 点后，离我 5 公里内的技师"
        :disabled="streaming"
        @keydown.enter.exact.prevent="handleSend"
      />
      <div class="ns-composer-actions">
        <span class="ns-text-muted">{{ stageText || "Shift+Enter 换行" }}</span>
        <div class="ns-row">
          <el-button v-if="streaming" @click="handleStop" aria-label="停止生成">停止生成</el-button>
          <el-button type="primary" :disabled="!canSend" @click="handleSend">发送</el-button>
        </div>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.ns-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--ns-space-3);
  margin-bottom: var(--ns-space-4);
}

.ns-header p {
  margin: var(--ns-space-1) 0 0;
}

.ns-agent-error {
  margin-bottom: var(--ns-space-3);
}

.ns-plan {
  margin-bottom: var(--ns-space-3);
}

.ns-plan-header {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--ns-space-2);
  margin-bottom: var(--ns-space-2);
}

.ns-plan-title {
  margin: 0;
  font-size: var(--ns-text-label);
  font-weight: 600;
}

.ns-plan-list {
  margin: 0;
  padding-left: var(--ns-space-4);
  display: flex;
  flex-direction: column;
  gap: var(--ns-space-1);
}

.ns-plan-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ns-space-2);
}

.ns-plan-desc {
  font-size: var(--ns-text-caption);
  color: var(--ns-fg);
}

.ns-messages {
  display: flex;
  flex-direction: column;
  gap: var(--ns-space-3);
  min-height: 320px;
  max-height: 56vh;
  overflow-y: auto;
  margin-bottom: var(--ns-space-3);
}

.ns-messages-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 240px;
}

.ns-message {
  display: flex;
}

.ns-message.is-user {
  justify-content: flex-end;
}

.ns-message-bubble {
  max-width: 86%;
  padding: var(--ns-space-3);
  border-radius: var(--ns-radius-card);
  background: var(--ns-color-gray-100);
  white-space: pre-wrap;
  word-break: break-word;
  font-size: var(--ns-text-body);
  line-height: var(--ns-line-height);
}

.ns-message.is-user .ns-message-bubble {
  background: var(--ns-primary);
  color: #fff;
}

.ns-confirm {
  border-color: var(--ns-warning);
  margin-bottom: var(--ns-space-3);
}

.ns-confirm-header {
  display: flex;
  align-items: center;
  gap: var(--ns-space-2);
  margin-bottom: var(--ns-space-2);
}

.ns-confirm-desc {
  margin: 0 0 var(--ns-space-2);
  color: var(--ns-fg);
}

.ns-confirm-args {
  margin: 0 0 var(--ns-space-3);
  padding: var(--ns-space-3);
  background: var(--ns-color-gray-50);
  border: 1px solid var(--ns-border);
  border-radius: var(--ns-radius-sm);
  font-size: var(--ns-text-caption);
  overflow-x: auto;
  max-height: 200px;
}

.ns-confirm-actions .el-button {
  min-height: var(--ns-touch-target);
}

.ns-composer {
  position: sticky;
  bottom: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ns-space-2);
  padding: var(--ns-space-3);
  background: var(--ns-surface);
  border: 1px solid var(--ns-border);
  border-radius: var(--ns-radius-card);
  box-shadow: var(--ns-shadow-sm);
}

.ns-composer-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--ns-space-2);
}

@media (max-width: 767px) {
  .ns-message-bubble {
    max-width: 92%;
  }

  .ns-composer-actions .el-button {
    min-height: var(--ns-touch-target);
  }
}
</style>
