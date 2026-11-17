/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 接口基地址，开发环境走 Vite 代理 /api */
  readonly VITE_API_BASE_URL: string;
  /** 应用标题 */
  readonly VITE_APP_TITLE: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
