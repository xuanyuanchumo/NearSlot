import { createPinia } from "pinia";
import piniaPluginPersistedstate from "pinia-plugin-persistedstate";

/**
 * Pinia 实例（模块级单例）。
 * request.ts / auth.ts 不在组件上下文内，需要显式传入 pinia 实例。
 */
export const pinia = createPinia();
pinia.use(piniaPluginPersistedstate);

export default pinia;
