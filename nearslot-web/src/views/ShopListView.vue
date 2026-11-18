<script setup lang="ts">
/** 首页 / 服务商列表：游标分页 + 三态 + 触底加载 */
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import PageState from "@/components/PageState.vue";
import { fetchShops } from "@/api/shop";
import { formatCents, formatDistance } from "@/utils/format";
import type { Shop } from "@/types/api";

const router = useRouter();

const shops = ref<Shop[]>([]);
const loading = ref(false);
const loadingMore = ref(false);
const errorMessage = ref("");
const cursor = ref<string | null>(null);
const hasMore = ref(true);
const keyword = ref("");

const pageState = computed(() => {
  if (loading.value) return "loading" as const;
  if (errorMessage.value) return "error" as const;
  if (shops.value.length === 0) return "empty" as const;
  return "ready" as const;
});

async function loadShops(reset = false): Promise<void> {
  if (reset) {
    loading.value = true;
    cursor.value = null;
    hasMore.value = true;
    errorMessage.value = "";
  } else {
    if (!hasMore.value || loadingMore.value) return;
    loadingMore.value = true;
  }

  try {
    // 后端是 page/size 偏移分页（GET /shops?page&size&typeId），不是游标分页。
    // 2026-11-17 审计修复：此前按 {cursor, limit} 请求、按 {cursor, hasMore} 解析，
    // 后端完全不认这两个参数，page.items 也是 undefined —— 首页列表恒空。
    const nextPage = reset ? 1 : (cursor.value ? Number(cursor.value) + 1 : 2);
    const page = await fetchShops({ page: nextPage, size: 12 });
    shops.value = reset ? page.items : shops.value.concat(page.items);
    cursor.value = String(nextPage);
    hasMore.value = shops.value.length < page.total;
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : "加载服务商列表失败";
    if (!reset) ElMessage.error(errorMessage.value);
  } finally {
    loading.value = false;
    loadingMore.value = false;
  }
}

function goDetail(shop: Shop): void {
  router.push({ name: "shop-detail", params: { id: shop.id } });
}

/** 触底加载（配合节流：简单判重即可，因为 loadingMore 已做闸门） */
function handleScroll(): void {
  const scrollBottom = window.innerHeight + window.scrollY;
  const threshold = document.body.offsetHeight - 200;
  if (scrollBottom >= threshold) void loadShops(false);
}

onMounted(() => {
  void loadShops(true);
  window.addEventListener("scroll", handleScroll, { passive: true });
});

onBeforeUnmount(() => {
  window.removeEventListener("scroll", handleScroll);
});
</script>

<template>
  <div class="ns-page">
    <header class="ns-header">
      <div>
        <h1 class="ns-title">附近服务商</h1>
        <p class="ns-text-muted">按服务项预约时段，服务者与履约方式由店铺配置（到店 / 上门）</p>
      </div>
      <div class="ns-row">
        <el-button text @click="router.push('/appointments')">
          <el-icon><Tickets /></el-icon>
          <span class="ns-btn-text">我的预约</span>
        </el-button>
        <el-button text @click="router.push('/agent')">
          <el-icon><ChatDotRound /></el-icon>
          <span class="ns-btn-text">AI 助手</span>
        </el-button>
      </div>
    </header>

    <el-input
      v-model="keyword"
      class="ns-search"
      placeholder="搜索服务商名称或服务类目"
      clearable
      size="large"
      @keyup.enter="loadShops(true)"
      @clear="loadShops(true)"
    >
      <template #append>
        <el-button :loading="loading" @click="loadShops(true)">搜索</el-button>
      </template>
    </el-input>

    <PageState
      :state="pageState"
      empty-text="附近还没有服务商"
      empty-action-text="重新加载"
      :error-text="errorMessage"
      :skeleton-rows="4"
      @action="loadShops(true)"
      @retry="loadShops(true)"
    >
      <el-row :gutter="16">
        <el-col
          v-for="shop in shops"
          :key="shop.id"
          :xs="24"
          :sm="12"
          :md="8"
          :lg="6"
          class="ns-col"
        >
          <article
            class="ns-shop ns-card"
            tabindex="0"
            @click="goDetail(shop)"
            @keyup.enter="goDetail(shop)"
          >
            <el-image
              v-if="shop.coverUrl"
              :src="shop.coverUrl"
              class="ns-shop-cover"
              fit="cover"
              lazy
            >
              <template #error>
                <div class="ns-shop-cover-placeholder">
                  <el-icon><Picture /></el-icon>
                </div>
              </template>
            </el-image>
            <div v-else class="ns-shop-cover-placeholder">
              <el-icon><Shop /></el-icon>
            </div>

            <div class="ns-shop-body">
              <h2 class="ns-shop-name">{{ shop.name }}</h2>
              <p class="ns-shop-desc">{{ shop.description || "暂无简介" }}</p>
              <div class="ns-shop-meta">
                <span class="ns-text-muted">{{ formatDistance(shop.distanceMeters) }}</span>
                <span class="ns-shop-price">起 {{ formatCents(shop.minPriceCents) }}</span>
              </div>
            </div>
          </article>
        </el-col>
      </el-row>

      <div class="ns-load-more">
        <el-button v-if="hasMore" :loading="loadingMore" text @click="loadShops(false)">
          {{ loadingMore ? "加载中…" : "加载更多" }}
        </el-button>
        <span v-else class="ns-text-muted">没有更多了</span>
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

.ns-search {
  margin-bottom: var(--ns-space-4);
}

.ns-col {
  margin-bottom: var(--ns-space-4);
}

.ns-shop {
  display: flex;
  flex-direction: column;
  height: 100%;
  cursor: pointer;
  transition: box-shadow var(--ns-motion-normal) var(--ns-ease-out);
}

.ns-shop:hover {
  box-shadow: var(--ns-shadow-md);
}

.ns-shop-cover,
.ns-shop-cover-placeholder {
  width: 100%;
  height: 132px;
  border-radius: var(--ns-radius-sm);
  background: var(--ns-color-gray-100);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--ns-color-gray-400);
  font-size: 28px;
  overflow: hidden;
}

.ns-shop-body {
  margin-top: var(--ns-space-3);
  display: flex;
  flex-direction: column;
  gap: var(--ns-space-1);
  flex: 1;
}

.ns-shop-name {
  margin: 0;
  font-size: var(--ns-text-body);
  font-weight: 600;
  color: var(--ns-fg);
}

.ns-shop-desc {
  margin: 0;
  color: var(--ns-fg-muted);
  font-size: var(--ns-text-caption);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.ns-shop-meta {
  margin-top: auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.ns-shop-price {
  color: var(--ns-danger);
  font-weight: 600;
  font-size: var(--ns-text-label);
}

.ns-load-more {
  display: flex;
  justify-content: center;
  padding: var(--ns-space-4) 0;
}

/* 移动端隐藏按钮文字，只留图标（仍保留 aria 名称） */
@media (max-width: 767px) {
  .ns-btn-text {
    display: none;
  }
}
</style>
