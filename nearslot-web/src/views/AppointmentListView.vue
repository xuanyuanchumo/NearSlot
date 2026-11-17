<script setup lang="ts">
/** 我的预约：列表 + 状态标签 + 取消（二次确认 + 防重复） */
import { computed, onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import PageState from "@/components/PageState.vue";
import { cancelAppointment, fetchAppointments } from "@/api/appointment";
import { APPOINTMENT_STATUS_TEXT, type Appointment, type AppointmentStatus } from "@/types/api";
import { formatCents, formatDateTimeInZone } from "@/utils/format";

const appointments = ref<Appointment[]>([]);
const loading = ref(false);
const errorMessage = ref("");
const cancellingId = ref("");

const pageState = computed(() => {
  if (loading.value) return "loading" as const;
  if (errorMessage.value) return "error" as const;
  if (appointments.value.length === 0) return "empty" as const;
  return "ready" as const;
});

/** 状态 -> Element Plus tag 类型 */
const STATUS_TAG: Record<AppointmentStatus, "success" | "warning" | "danger" | "info" | "primary"> =
  {
    PENDING: "info",
    HELD: "warning",
    CONFIRMED: "success",
    CANCELLED: "info",
    EXPIRED: "danger",
    COMPLETED: "success",
    NO_SHOW: "danger",
  };

function tagType(status: AppointmentStatus) {
  return STATUS_TAG[status] ?? "info";
}

function statusText(status: AppointmentStatus): string {
  return APPOINTMENT_STATUS_TEXT[status] ?? status;
}

async function loadAppointments(): Promise<void> {
  loading.value = true;
  errorMessage.value = "";
  try {
    const page = await fetchAppointments({ limit: 20 });
    appointments.value = page.items;
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : "加载预约列表失败";
  } finally {
    loading.value = false;
  }
}

async function handleCancel(item: Appointment): Promise<void> {
  if (cancellingId.value) return; // 防重复点击
  try {
    await ElMessageBox.confirm(
      `确认取消 ${formatDateTimeInZone(item.startsAt, item.tzId)} 的预约吗？`,
      "取消预约",
      { type: "warning", confirmButtonText: "确认取消", cancelButtonText: "再想想" },
    );
  } catch {
    return; // 用户放弃
  }

  cancellingId.value = item.id;
  try {
    await cancelAppointment(item.id);
    ElMessage.success("预约已取消");
    // 服务端状态回查优先于本地修改
    await loadAppointments();
  } catch {
    // 错误提示已在拦截器统一出口处理
  } finally {
    cancellingId.value = "";
  }
}

function canCancel(item: Appointment): boolean {
  return item.status === "HELD" || item.status === "CONFIRMED" || item.status === "PENDING";
}

onMounted(() => {
  void loadAppointments();
});
</script>

<template>
  <div class="ns-page">
    <header class="ns-header">
      <div>
        <h1 class="ns-title">我的预约</h1>
        <p class="ns-text-muted">查看预约状态、取消或前往支付</p>
      </div>
      <div class="ns-row">
        <el-button text :loading="loading" @click="loadAppointments">
          <el-icon><Refresh /></el-icon>
          <span class="ns-btn-text">刷新</span>
        </el-button>
        <el-button text @click="$router.push('/')">去逛逛</el-button>
      </div>
    </header>

    <PageState
      :state="pageState"
      empty-text="还没有预约记录"
      empty-action-text="去选一个时段"
      :error-text="errorMessage"
      :skeleton-rows="4"
      @action="$router.push('/')"
      @retry="loadAppointments"
    >
      <div class="ns-list">
        <article v-for="item in appointments" :key="item.id" class="ns-item ns-card">
          <div class="ns-item-main">
            <div class="ns-item-title">
              <strong>{{ item.shopName || "服务商" }}</strong>
              <el-tag :type="tagType(item.status)" size="small" disable-transitions>
                {{ statusText(item.status) }}
              </el-tag>
            </div>
            <p class="ns-text-muted">
              {{ formatDateTimeInZone(item.startsAt, item.tzId) }}
              <span v-if="item.technicianName"> · 技师 {{ item.technicianName }}</span>
            </p>
            <p class="ns-text-muted">
              预约单号 {{ item.id }}
              <span v-if="item.amountCents !== undefined">
                · {{ formatCents(item.amountCents) }}</span
              >
            </p>
          </div>

          <div class="ns-item-actions">
            <el-button
              v-if="canCancel(item)"
              type="danger"
              plain
              size="small"
              :loading="cancellingId === item.id"
              :disabled="Boolean(cancellingId) && cancellingId !== item.id"
              @click="handleCancel(item)"
            >
              取消预约
            </el-button>
          </div>
        </article>
      </div>
    </PageState>
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

.ns-list {
  display: flex;
  flex-direction: column;
  gap: var(--ns-space-3);
}

.ns-item {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--ns-space-3);
}

.ns-item-main {
  display: flex;
  flex-direction: column;
  gap: var(--ns-space-1);
  min-width: 0;
}

.ns-item-title {
  display: flex;
  align-items: center;
  gap: var(--ns-space-2);
}

.ns-item-main p {
  margin: 0;
  word-break: break-all;
}

@media (max-width: 767px) {
  .ns-item-actions {
    width: 100%;
  }

  .ns-item-actions .el-button {
    width: 100%;
    min-height: var(--ns-touch-target);
  }

  .ns-btn-text {
    display: none;
  }
}
</style>
