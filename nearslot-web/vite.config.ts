import { fileURLToPath, URL } from "node:url";
import vue from "@vitejs/plugin-vue";
import AutoImport from "unplugin-auto-import/vite";
import Components from "unplugin-vue-components/vite";
import { ElementPlusResolver } from "unplugin-vue-components/resolvers";
import { defineConfig } from "vite-plus";

// Element Plus 样式在 src/main.ts 中整包引入（import 'element-plus/dist/index.css'），
// 因此解析器统一关闭按需样式，避免重复注入。组件与 API 仍然按需自动引入。
const elementPlusResolver = () => ElementPlusResolver({ importStyle: false });

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({
      imports: ["vue", "vue-router", "pinia"],
      dts: "src/types/auto-imports.d.ts",
      resolvers: [elementPlusResolver()],
    }),
    Components({
      dts: "src/types/components.d.ts",
      dirs: ["src/components"],
      resolvers: [elementPlusResolver()],
    }),
  ],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  server: {
    host: "127.0.0.1",
    port: 5173,
    proxy: {
      // 后端 Spring Boot 默认 8081；/api 前缀由后端 context-path 或网关承接
      "/api": {
        target: "http://localhost:8081",
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: "dist",
    assetsDir: "assets",
    sourcemap: false,
    chunkSizeWarningLimit: 1200,
  },
  test: {
    environment: "node",
    include: ["src/**/*.spec.ts"],
    passWithNoTests: true,
  },
});
