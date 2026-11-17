/**
 * 用户会话 store
 *
 * 安全约束（docs/standards/frontend.md §2）：
 * - 只持久化 accessToken / userId / level 三项；
 * - RT 永不落地 localStorage（由后端 HttpOnly Cookie 承载），
 *   persist 配置里显式 omit，杜绝误持久化；
 * - token 禁止 console 打印（hmdp-vue3 反模式）。
 */
import { defineStore } from "pinia";
import { ref } from "vue";
import type { UserLevel } from "@/types/api";

export interface UserProfile {
  userId?: string;
  level?: UserLevel;
}

export const useUserStore = defineStore(
  "user",
  () => {
    /** 访问令牌 AT，仅存内存 + persist 白名单 */
    const accessToken = ref("");
    /** 用户 id（雪花 ID，string） */
    const userId = ref("");
    /** 会员等级 0-3 */
    const level = ref<UserLevel>(0);

    /** 写入 AT（携带 profile 时同步 userId / level） */
    function setToken(token: string, profile?: UserProfile): void {
      const normalized = (token ?? "").trim();
      accessToken.value = normalized;
      if (profile?.userId !== undefined) userId.value = profile.userId;
      if (profile?.level !== undefined) level.value = profile.level;
    }

    /** 登录成功后写入会话 */
    function login(payload: { accessToken: string; userId: string; level?: UserLevel }): void {
      setToken(payload.accessToken, { userId: payload.userId, level: payload.level ?? 0 });
    }

    /** 登出：清空全部会话字段（RT Cookie 由后端 /auth/logout 失效） */
    function logout(): void {
      accessToken.value = "";
      userId.value = "";
      level.value = 0;
    }

    return { accessToken, userId, level, setToken, login, logout };
  },
  {
    // store key 固定为 nearslot-user
    persist: {
      key: "nearslot-user",
      storage: localStorage,
      // 白名单持久化：显式列出三项，任何新增字段默认不落盘（RT 绝不在其中）
      pick: ["accessToken", "userId", "level"],
    },
  },
);
