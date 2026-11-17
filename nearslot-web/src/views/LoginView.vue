<script setup lang="ts">
/** 登录页：账号密码登录，成功写入 Pinia 并按 redirect 回跳 */
import { reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, type FormInstance, type FormRules } from "element-plus";
import { login as loginApi } from "@/api/auth";
import { useUserStore } from "@/stores/user";

const router = useRouter();
const route = useRoute();
const userStore = useUserStore();

const formRef = ref<FormInstance>();
const loading = ref(false);

const form = reactive({
  username: "",
  password: "",
});

const rules: FormRules<typeof form> = {
  username: [
    { required: true, message: "请输入用户名", trigger: "blur" },
    { min: 3, max: 32, message: "用户名长度为 3-32 个字符", trigger: "blur" },
  ],
  password: [
    { required: true, message: "请输入密码", trigger: "blur" },
    { min: 6, max: 64, message: "密码长度为 6-64 个字符", trigger: "blur" },
  ],
};

async function handleSubmit(): Promise<void> {
  if (!formRef.value || loading.value) return;
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;

  loading.value = true;
  try {
    const result = await loginApi({ username: form.username, password: form.password });
    userStore.login({
      accessToken: result.accessToken,
      userId: result.userId,
      level: result.level ?? 0,
    });
    ElMessage.success("登录成功");
    const redirect = typeof route.query.redirect === "string" ? route.query.redirect : "/";
    await router.replace(redirect);
  } catch {
    // 错误提示已在 axios 拦截器统一出口处理
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="ns-login ns-page">
    <div class="ns-login-card ns-card">
      <header class="ns-login-header">
        <h1 class="ns-title">NearSlot 时段预约</h1>
        <p class="ns-text-muted">登录后即可抢约时段、查看预约与管理 AI 助手</p>
      </header>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        size="large"
        @submit.prevent="handleSubmit"
      >
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="form.username"
            placeholder="请输入用户名"
            autocomplete="username"
            clearable
            :prefix-icon="'User'"
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            autocomplete="current-password"
            show-password
            :prefix-icon="'Lock'"
            @keyup.enter="handleSubmit"
          />
        </el-form-item>

        <el-button
          type="primary"
          class="ns-login-submit"
          :loading="loading"
          :disabled="loading"
          native-type="submit"
          @click="handleSubmit"
        >
          登录
        </el-button>
      </el-form>

      <el-divider>
        <span class="ns-text-muted">或</span>
      </el-divider>

      <el-button text class="ns-login-guest" @click="router.push('/')">
        先随便逛逛
        <el-icon class="el-icon--right"><ArrowRight /></el-icon>
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.ns-login {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: var(--ns-space-4);
}

.ns-login-card {
  width: 100%;
  max-width: 400px;
  padding: var(--ns-space-6);
}

.ns-login-header {
  margin-bottom: var(--ns-space-6);
  text-align: center;
}

.ns-login-header p {
  margin: var(--ns-space-2) 0 0;
}

.ns-login-submit {
  width: 100%;
  min-height: var(--ns-touch-target);
}

.ns-login-guest {
  width: 100%;
}
</style>
