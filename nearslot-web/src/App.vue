<script setup lang="ts">
// 根组件只承载路由出口，布局交给各视图自己决定
</script>

<template>
  <router-view v-slot="{ Component }">
    <transition name="ns-fade" mode="out-in">
      <component :is="Component" />
    </transition>
  </router-view>
</template>

<style scoped>
.ns-fade-enter-active,
.ns-fade-leave-active {
  transition: opacity var(--ns-motion-fast) var(--ns-ease-out);
}

.ns-fade-enter-from,
.ns-fade-leave-to {
  opacity: 0;
}

@media (prefers-reduced-motion: reduce) {
  .ns-fade-enter-active,
  .ns-fade-leave-active {
    transition-duration: 0.01ms;
  }
}
</style>
