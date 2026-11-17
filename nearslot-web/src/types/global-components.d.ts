/**
 * 全局组件类型声明
 *
 * main.ts 中通过 app.use(ElementPlus) 与 ElementPlusIconsVue 全量注册，
 * 这里补齐模板类型，保证 vue-tsc --noEmit 能解析 el-* 与该文件中列出的图标组件。
 * 注意：unplugin-vue-components 生成的 src/types/components.d.ts 会与本文件合并（interface 合并）。
 */
declare module "vue" {
  export interface GlobalComponents {
    // 常用 Element Plus 组件（与 ElementPlusResolver 生成的 components.d.ts 使用同一模块说明符，
    // 保证 interface 合并时类型完全一致，不会出现 "must have the same type" 冲突）
    ElAlert: (typeof import("element-plus/es"))["ElAlert"];
    ElButton: (typeof import("element-plus/es"))["ElButton"];
    ElCol: (typeof import("element-plus/es"))["ElCol"];
    ElDatePicker: (typeof import("element-plus/es"))["ElDatePicker"];
    ElDivider: (typeof import("element-plus/es"))["ElDivider"];
    ElEmpty: (typeof import("element-plus/es"))["ElEmpty"];
    ElForm: (typeof import("element-plus/es"))["ElForm"];
    ElFormItem: (typeof import("element-plus/es"))["ElFormItem"];
    ElIcon: (typeof import("element-plus/es"))["ElIcon"];
    ElImage: (typeof import("element-plus/es"))["ElImage"];
    ElInput: (typeof import("element-plus/es"))["ElInput"];
    ElPageHeader: (typeof import("element-plus/es"))["ElPageHeader"];
    ElResult: (typeof import("element-plus/es"))["ElResult"];
    ElRow: (typeof import("element-plus/es"))["ElRow"];
    ElSkeleton: (typeof import("element-plus/es"))["ElSkeleton"];
    ElTag: (typeof import("element-plus/es"))["ElTag"];

    // 全量注册的 @element-plus/icons-vue 图标（仅列模板中实际使用的）
    ArrowRight: (typeof import("@element-plus/icons-vue"))["ArrowRight"];
    ChatDotRound: (typeof import("@element-plus/icons-vue"))["ChatDotRound"];
    CircleCheck: (typeof import("@element-plus/icons-vue"))["CircleCheck"];
    CircleClose: (typeof import("@element-plus/icons-vue"))["CircleClose"];
    Loading: (typeof import("@element-plus/icons-vue"))["Loading"];
    Lock: (typeof import("@element-plus/icons-vue"))["Lock"];
    Picture: (typeof import("@element-plus/icons-vue"))["Picture"];
    Refresh: (typeof import("@element-plus/icons-vue"))["Refresh"];
    Shop: (typeof import("@element-plus/icons-vue"))["Shop"];
    Tickets: (typeof import("@element-plus/icons-vue"))["Tickets"];
    User: (typeof import("@element-plus/icons-vue"))["User"];
    WarningFilled: (typeof import("@element-plus/icons-vue"))["WarningFilled"];
  }
}

export {};
