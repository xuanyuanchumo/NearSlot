<script setup lang="ts">
/** 404 页面：独立路由，给返回入口 */
import { useRouter } from "vue-router";

const router = useRouter();
</script>

<template>
  <div class="ns-404 ns-page">
    <el-result icon="warning" title="404" sub-title="页面不存在或已被移除">
      <template #extra>
        <div class="ns-row">
          <el-button type="primary" @click="router.push('/')">返回首页</el-button>
          <el-button @click="router.back()">返回上一页</el-button>
        </div>
      </template>
    </el-result>
  </div>
</template>

<style scoped>
.ns-404 {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
}
</style>
