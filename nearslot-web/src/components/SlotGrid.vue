<script setup lang="ts">
/**
 * SlotGrid 时段网格
 *
 * 契约（docs/research/11-frontend-ux-deep.md §1.2 / §2.2）：
 * - 状态：available / held / booked / disabled / past，后四者禁用并说明原因；
 * - a11y：外层 role=radiogroup，格子 role=radio + aria-checked，方向键移动；
 * - 响应式：容器查询决定列数（移动 3 列 / 平板 5 列 / 桌面 7 列）；
 * - 前端不做并发裁决：状态完全来自服务端，本地不占位。
 */
import type { TimeSlot } from "@/types/api";
import { SLOT_STATUS_TEXT } from "@/types/api";
import { formatCents, formatTimeInZone } from "@/utils/format";

interface Props {
  /** 当前日期的时段列表 */
  slots: TimeSlot[];
  /** 时区（IANA），用于渲染墙钟时间 */
  tzId: string;
  /** 已选中的时段 id */
  selectedId?: string;
  /** 是否禁用整个网格（如请求中） */
  disabled?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  selectedId: "",
  disabled: false,
});

const emit = defineEmits<{
  (e: "select", slot: TimeSlot): void;
  (e: "unselect", slot: TimeSlot): void;
}>();

/** 是否可点：只有 available 且整体未禁用 */
function isClickable(slot: TimeSlot): boolean {
  return !props.disabled && slot.status === "available";
}

/** 时段展示角标文案 */
function statusLabel(slot: TimeSlot): string {
  if (slot.status === "available") return formatCents(slot.priceCents);
  return slot.reason || SLOT_STATUS_TEXT[slot.status];
}

function handleClick(slot: TimeSlot): void {
  if (props.selectedId === slot.id) {
    emit("unselect", slot);
    return;
  }
  if (!isClickable(slot)) return;
  emit("select", slot);
}

/** 方向键在网格内移动焦点（左右 ±1，上下按每行列数） */
function handleKeydown(event: KeyboardEvent, index: number, columns: number): void {
  const keyMap: Record<string, number> = {
    ArrowRight: 1,
    ArrowLeft: -1,
    ArrowDown: columns,
    ArrowUp: -columns,
  };
  const delta = keyMap[event.key];
  if (delta === undefined) return;
  event.preventDefault();
  const targetIndex = Math.min(Math.max(index + delta, 0), props.slots.length - 1);
  const grid = (event.currentTarget as HTMLElement).closest(".ns-slot-grid");
  const buttons = grid?.querySelectorAll<HTMLButtonElement>(".ns-slot");
  buttons?.[targetIndex]?.focus();
}

/** 一行的列数：与 CSS 容器查询保持一致的近似值，用于上下方向键（按键时实时读取，避免陈旧值） */
function columnsPerRow(): number {
  const width = window.innerWidth;
  if (width < 768) return 3;
  return width < 1200 ? 5 : 7;
}
</script>

<template>
  <div v-if="slots.length === 0" class="ns-slot-empty">
    <el-empty description="当日暂无可预约时段" :image-size="80" />
  </div>

  <div v-else class="ns-slot-wrap">
    <div class="ns-slot-grid" role="radiogroup" aria-label="可预约时段">
      <button
        v-for="(slot, index) in slots"
        :key="slot.id"
        type="button"
        class="ns-slot"
        :class="[`is-${slot.status}`, { 'is-selected': selectedId === slot.id }]"
        role="radio"
        :aria-checked="selectedId === slot.id"
        :aria-disabled="!isClickable(slot)"
        :disabled="!isClickable(slot)"
        :title="
          isClickable(slot) ? `${formatTimeInZone(slot.startsAt, tzId)} 可预约` : statusLabel(slot)
        "
        :tabindex="index === 0 || selectedId === slot.id ? 0 : -1"
        @click="handleClick(slot)"
        @keydown="handleKeydown($event, index, columnsPerRow())"
      >
        <span class="ns-slot-time">{{ formatTimeInZone(slot.startsAt, tzId) }}</span>
        <span class="ns-slot-meta">{{ statusLabel(slot) }}</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
/* 容器查询：组件放到任何宽度容器内都能自适应，不依赖视口断点。
   注意：容器查询只能作用于"容器的后代"，不能给容器自身设样式，
   因此用外层 .ns-slot-wrap 声明 container-type，内层 grid 响应查询。 */
.ns-slot-wrap {
  container-type: inline-size;
}

.ns-slot-grid {
  display: grid;
  gap: var(--ns-space-2);
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

@container (min-width: 480px) {
  .ns-slot-grid {
    grid-template-columns: repeat(5, minmax(0, 1fr));
  }
}

@container (min-width: 720px) {
  .ns-slot-grid {
    grid-template-columns: repeat(7, minmax(0, 1fr));
  }
}

.ns-slot {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  /* 触控目标 ≥ 44px */
  min-height: var(--ns-touch-target);
  padding: var(--ns-space-2) var(--ns-space-1);
  border: 1px solid var(--ns-border);
  border-radius: var(--ns-radius-card);
  background: var(--ns-surface);
  color: var(--ns-fg);
  font-family: inherit;
  cursor: pointer;
  transition:
    background var(--ns-motion-fast) var(--ns-ease-out),
    border-color var(--ns-motion-fast) var(--ns-ease-out),
    transform var(--ns-motion-fast) var(--ns-ease-out);
}

.ns-slot-time {
  font-size: var(--ns-text-body);
  font-weight: 600;
  line-height: 1.2;
}

.ns-slot-meta {
  font-size: var(--ns-text-caption);
  color: var(--ns-fg-muted);
  line-height: 1.2;
}

/* 可预约 */
.ns-slot.is-available {
  background: var(--ns-state-available);
  border-color: var(--ns-primary);
  color: var(--ns-state-available-fg);
}

.ns-slot.is-available:hover {
  transform: translateY(-1px);
  box-shadow: var(--ns-shadow-sm);
}

/* 已选中 */
.ns-slot.is-selected {
  background: var(--ns-primary);
  border-color: var(--ns-primary);
  color: #fff;
}

.ns-slot.is-selected .ns-slot-meta {
  color: rgb(255 255 255 / 85%);
}

/* 锁定中 / 已预约 / 不可约 / 已过期：统一置灰（非纯颜色区分，文案同时说明） */
.ns-slot.is-held {
  background: var(--ns-state-held);
  color: var(--ns-state-held-fg);
  border-color: var(--ns-state-held-fg);
}

.ns-slot.is-booked,
.ns-slot.is-disabled,
.ns-slot.is-past {
  background: var(--ns-state-booked);
  color: var(--ns-state-booked-fg);
  border-color: var(--ns-border);
  cursor: not-allowed;
}

.ns-slot:disabled {
  cursor: not-allowed;
}

.ns-slot-empty {
  padding: var(--ns-space-6) 0;
}

@media (prefers-reduced-motion: reduce) {
  .ns-slot {
    transition-duration: 0.01ms;
  }

  .ns-slot.is-available:hover {
    transform: none;
  }
}
</style>
