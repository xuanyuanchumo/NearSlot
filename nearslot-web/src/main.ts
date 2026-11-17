import { createApp } from "vue";
import ElementPlus from "element-plus";
import zhCn from "element-plus/es/locale/lang/zh-cn";
import * as ElementPlusIconsVue from "@element-plus/icons-vue";
import "element-plus/dist/index.css";

import App from "@/App.vue";
import router from "@/router";
import { pinia } from "@/stores";
import "@/styles/index.css";

const app = createApp(App);

// 图标全局注册（模板中可直接 <el-icon><Search /></el-icon>）
for (const [name, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(name, component);
}

app.use(pinia);
app.use(router);
app.use(ElementPlus, { locale: zhCn });

app.mount("#app");
