<script setup lang="ts">
/**
 * PageState 三态占位组件（骨架 / 空 / 错误）
 * 统一空态给 CTA、错误态展示后端 code 文案并可一键重试（docs/standards/frontend.md §4）。
 */
interface Props {
  /** 当前状态 */
  state: "loading" | "empty" | "error" | "ready";
  /** 空态文案 */
  emptyText?: string;
  /** 空态按钮文案 */
  emptyActionText?: string;
  /** 错误文案 */
  errorText?: string;
  /** 骨架行数 */
  skeletonRows?: number;
}

const props = withDefaults(defineProps<Props>(), {
  emptyText: "暂无数据",
  emptyActionText: "",
  errorText: "加载失败，请稍后重试",
  skeletonRows: 3,
});

const emit = defineEmits<{
  (e: "action"): void;
  (e: "retry"): void;
}>();
</script>

<template>
  <!-- 加载：骨架屏，aria-busy 告知辅助技术 -->
  <div v-if="props.state === 'loading'" class="ns-skeleton" aria-busy="true" aria-label="加载中">
    <el-skeleton :rows="props.skeletonRows" animated />
  </div>

  <!-- 错误：role=alert，展示错误码文案 + 重试 -->
  <div v-else-if="props.state === 'error'" class="ns-state" role="alert">
    <el-result icon="error" title="加载失败" :sub-title="props.errorText">
      <template #extra>
        <el-button type="primary" @click="emit('retry')">重新加载</el-button>
      </template>
    </el-result>
  </div>

  <!-- 空态：必须给行动指引 -->
  <div v-else-if="props.state === 'empty'" class="ns-state" role="status">
    <el-empty :description="props.emptyText" :image-size="96">
      <el-button v-if="props.emptyActionText" type="primary" @click="emit('action')">
        {{ props.emptyActionText }}
      </el-button>
    </el-empty>
  </div>

  <!-- ready：交给调用方渲染真实内容 -->
  <slot v-else />
</template>

<style scoped>
.ns-state {
  padding: var(--ns-space-4) 0;
}

.ns-skeleton {
  padding: var(--ns-space-4);
  background: var(--ns-surface);
  border-radius: var(--ns-radius-card);
}
</style>
