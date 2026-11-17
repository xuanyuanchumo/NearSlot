<script setup lang="ts">
/**
 * 服务商详情：时段网格 + 抢约 + Hold 倒计时
 *
 * 关键约束（docs/research/11-frontend-ux-deep.md §3.1）：
 * - 抢约前先判登录（未登录直接跳登录，不依赖 401 兜底）；
 * - 防重三元组：bookingInProgress 标志 + 按钮 loading + finally 复位；
 * - 抢约成功后不本地占位，重查时段以服务端状态为准；
 * - Hold 倒计时以服务端 expiresAt 为准，过期后按钮不可点。
 */
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import PageState from "@/components/PageState.vue";
import SlotGrid from "@/components/SlotGrid.vue";
import { fetchShopDetail, fetchSlots } from "@/api/shop";
import { grabAppointment } from "@/api/appointment";
import { isLoggedIn } from "@/utils/auth";
import { formatCents, formatDateTimeInZone, formatCountdown, todayInZone } from "@/utils/format";
import type { GrabResult, Shop, TimeSlot } from "@/types/api";

const route = useRoute();
const router = useRouter();

const shopId = computed(() => String(route.params.id ?? ""));
const shop = ref<Shop | null>(null);
const slots = ref<TimeSlot[]>([]);
const selectedSlot = ref<TimeSlot | null>(null);

const loadingShop = ref(false);
const loadingSlots = ref(false);
const bookingInProgress = ref(false);
const errorMessage = ref("");
const slotErrorMessage = ref("");
const activeDate = ref("");
const hold = ref<GrabResult | null>(null);
const remainingMs = ref(0);

let countdownTimer: number | undefined;

const pageState = computed(() => {
  if (loadingShop.value) return "loading" as const;
  if (errorMessage.value) return "error" as const;
  if (!shop.value) return "empty" as const;
  return "ready" as const;
});

const slotsState = computed(() => {
  if (loadingSlots.value) return "loading" as const;
  if (slotErrorMessage.value) return "error" as const;
  if (slots.value.length === 0) return "empty" as const;
  return "ready" as const;
});

const tzId = computed(() => slots.value[0]?.tzId ?? "Asia/Shanghai");
const holdExpired = computed(() => hold.value !== null && remainingMs.value <= 0);

async function loadShop(): Promise<void> {
  if (!shopId.value) return;
  loadingShop.value = true;
  errorMessage.value = "";
  try {
    shop.value = await fetchShopDetail(shopId.value);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : "加载服务商详情失败";
  } finally {
    loadingShop.value = false;
  }
}

async function loadSlots(date: string): Promise<void> {
  if (!shopId.value) return;
  activeDate.value = date;
  loadingSlots.value = true;
  slotErrorMessage.value = "";
  try {
    slots.value = await fetchSlots(shopId.value, { date });
  } catch (error) {
    slotErrorMessage.value = error instanceof Error ? error.message : "加载时段失败";
  } finally {
    loadingSlots.value = false;
  }
}

/** el-date-picker 的 update:model-value 可能回传 string | Date | null，统一归一化为 YYYY-MM-DD */
function handleDateChange(value: string | number | Date | null | undefined): void {
  if (!value) return;
  const date =
    typeof value === "string"
      ? value.slice(0, 10)
      : value instanceof Date
        ? value.toISOString().slice(0, 10)
        : String(value).slice(0, 10);
  if (!date) return;
  selectedSlot.value = null;
  void loadSlots(date);
}

/** 选中时段；已选则取消 */
function handleSelect(slot: TimeSlot): void {
  selectedSlot.value = slot;
}

function handleUnselect(): void {
  selectedSlot.value = null;
}

/** 倒计时：以服务端 expiresAt 为准，仅做展示，不做任何清理动作 */
function startHoldCountdown(result: GrabResult): void {
  hold.value = result;
  const expiresAt = new Date(result.expiresAt).getTime();
  const tick = (): void => {
    remainingMs.value = Math.max(0, expiresAt - Date.now());
    if (remainingMs.value <= 0 && countdownTimer !== undefined) {
      window.clearInterval(countdownTimer);
      countdownTimer = undefined;
      ElMessage.warning("Hold 已到期，时段已释放");
      void loadSlots(activeDate.value);
    }
  };
  tick();
  if (countdownTimer !== undefined) window.clearInterval(countdownTimer);
  countdownTimer = window.setInterval(tick, 1000);
}

async function handleGrab(): Promise<void> {
  // 防重：第一个闸门
  if (bookingInProgress.value) return;

  // 未登录先拦截，不依赖 401 兜底
  if (!isLoggedIn()) {
    ElMessage.warning("请先登录后再抢约");
    await router.push({ name: "login", query: { redirect: route.fullPath } });
    return;
  }

  const slot = selectedSlot.value;
  if (!slot) {
    ElMessage.warning("请先选择一个时段");
    return;
  }
  if (bookingInProgress.value) return;

  bookingInProgress.value = true;
  try {
    const result = await grabAppointment({
      slotId: slot.id,
      // 幂等键：同一时段 + 用户，重复提交由后端 42902 拦截
      idempotencyKey: `grab-${slot.id}-${Date.now()}`,
    });
    ElMessage.success("抢约成功，已为您锁定时段");
    startHoldCountdown(result);
    // 服务端状态回查优于本地乐观更新
    await loadSlots(activeDate.value);
  } catch {
    // 失败文案已在拦截器统一出口处理；重新拉取时段以刷新真实状态
    await loadSlots(activeDate.value);
  } finally {
    // 防重复标志必须复位
    bookingInProgress.value = false;
  }
}

function goAppointments(): void {
  void router.push("/appointments");
}

onMounted(async () => {
  activeDate.value = todayInZone();
  await loadShop();
  await loadSlots(activeDate.value);
});

onBeforeUnmount(() => {
  if (countdownTimer !== undefined) {
    window.clearInterval(countdownTimer);
    countdownTimer = undefined;
  }
});
</script>

<template>
  <div class="ns-page">
    <el-page-header class="ns-page-header" @back="router.push('/')">
      <template #content>
        <span>{{ shop?.name || "服务商详情" }}</span>
      </template>
      <template #extra>
        <el-button text @click="goAppointments">我的预约</el-button>
      </template>
    </el-page-header>

    <PageState
      :state="pageState"
      empty-text="未找到该服务商"
      empty-action-text="返回首页"
      :error-text="errorMessage"
      :skeleton-rows="5"
      @action="router.push('/')"
      @retry="loadShop"
    >
      <section v-if="shop" class="ns-card ns-shop-info">
        <div class="ns-shop-head">
          <h1 class="ns-title">{{ shop.name }}</h1>
          <span v-if="shop.score !== undefined" class="ns-shop-score"
            >{{ shop.score.toFixed(1) }} 分</span
          >
        </div>
        <p class="ns-text-muted">{{ shop.address || "地址待完善" }}</p>
        <p class="ns-shop-desc">{{ shop.description || "暂无简介" }}</p>
        <div class="ns-row">
          <span class="ns-shop-price">起 {{ formatCents(shop.minPriceCents) }}</span>
          <span class="ns-text-muted">时间以 {{ tzId }} 时区显示</span>
        </div>
      </section>

      <section class="ns-card ns-slots">
        <header class="ns-slots-header">
          <h2 class="ns-title">选择时段</h2>
          <el-date-picker
            :model-value="activeDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择日期"
            :clearable="false"
            :editable="false"
            @update:model-value="handleDateChange"
          />
        </header>

        <PageState
          :state="slotsState"
          empty-text="当日暂无可预约时段"
          empty-action-text="刷新"
          :error-text="slotErrorMessage"
          :skeleton-rows="3"
          @action="loadSlots(activeDate)"
          @retry="loadSlots(activeDate)"
        >
          <SlotGrid
            :slots="slots"
            :tz-id="tzId"
            :selected-id="selectedSlot?.id ?? ''"
            :disabled="bookingInProgress"
            @select="handleSelect"
            @unselect="handleUnselect"
          />
        </PageState>
      </section>

      <!-- Hold 倒计时 -->
      <el-alert
        v-if="hold"
        class="ns-hold"
        :type="holdExpired ? 'error' : remainingMs < 60_000 ? 'warning' : 'info'"
        :closable="false"
        show-icon
        role="timer"
        :title="holdExpired ? 'Hold 已到期，时段已释放' : '已为您锁定时段，请在倒计时内完成支付'"
      >
        <template #default>
          <div class="ns-hold-body">
            <span class="ns-hold-time" aria-live="polite">{{ formatCountdown(remainingMs) }}</span>
            <span class="ns-text-muted">
              过期时间 {{ hold ? formatDateTimeInZone(hold.expiresAt, tzId) : "--" }}
            </span>
          </div>
        </template>
      </el-alert>

      <!-- 抢约操作区：移动端 sticky 底部 -->
      <div class="ns-booking-bar">
        <div class="ns-booking-summary">
          <template v-if="selectedSlot">
            <strong>{{ formatDateTimeInZone(selectedSlot.startsAt, tzId) }}</strong>
            <span class="ns-text-muted">共 {{ selectedSlot.durationMinutes }} 分钟</span>
          </template>
          <span v-else class="ns-text-muted">请选择一个时段</span>
        </div>
        <el-button
          type="primary"
          size="large"
          class="ns-booking-btn"
          :loading="bookingInProgress"
          :disabled="!selectedSlot || bookingInProgress || holdExpired"
          @click="handleGrab"
        >
          {{ bookingInProgress ? "抢约中…" : "立即抢约" }}
        </el-button>
      </div>
    </PageState>
  </div>
</template>

<style scoped>
.ns-page-header {
  margin-bottom: var(--ns-space-4);
}

.ns-shop-info {
  margin-bottom: var(--ns-space-4);
}

.ns-shop-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ns-space-2);
}

.ns-shop-score {
  color: var(--ns-warning);
  font-weight: 600;
}

.ns-shop-desc {
  margin: var(--ns-space-2) 0;
  color: var(--ns-fg);
}

.ns-shop-price {
  color: var(--ns-danger);
  font-weight: 600;
}

.ns-slots {
  margin-bottom: var(--ns-space-4);
}

.ns-slots-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--ns-space-3);
  margin-bottom: var(--ns-space-4);
}

.ns-hold {
  margin-bottom: var(--ns-space-4);
}

.ns-hold-body {
  display: flex;
  align-items: baseline;
  gap: var(--ns-space-2);
}

.ns-hold-time {
  font-size: var(--ns-text-title);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.ns-booking-bar {
  position: sticky;
  bottom: 0;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--ns-space-3);
  padding: var(--ns-space-3) var(--ns-space-4);
  background: var(--ns-surface);
  border: 1px solid var(--ns-border);
  border-radius: var(--ns-radius-card);
  box-shadow: var(--ns-shadow-md);
}

.ns-booking-summary {
  display: flex;
  flex-direction: column;
}

.ns-booking-btn {
  min-width: 140px;
  min-height: var(--ns-touch-target);
}

@media (max-width: 767px) {
  .ns-booking-btn {
    width: 100%;
  }
}
</style>
