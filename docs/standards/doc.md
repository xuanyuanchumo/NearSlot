---
title: 文档规范
status: current
source_of_truth:
  - ../../docs/standards/doc.md
updated: 2026-09-05
---

# 文档规范（继承 PickNear md/规范 治理体系）

## 1. 唯一事实源

- 每份文档 frontmatter 必填：title / status（draft|current|legacy）/ source_of_truth（事实源路径数组）/ updated。
- 一个主题只有一份 current；被取代的文档标 legacy 并加 superseded_by 指向新文档（历史靠 Git，不建 archive/ 目录）。
- 默认不新建文档：先查现有模块文档能否扩展；新建需在模块 README 的主题索引登记。

## 2. 目录与导航

- 一级目录=模块域（research/architecture/api/database/deployment/performance/security/testing/standards/quality/agents）。
- 每目录 README（或根 README 路由表）：定位一句话 + 主题索引 + 「什么任务读什么文档」路由表。
- docs/README.md 为文档中心入口，含全目录导航与治理规则。

## 3. 确定性检查（check_docs.py，CI 门禁）

- frontmatter 必填字段存在且值合法。
- 链接目标存在（相对路径）。
- 每份 current 文档可从所在目录索引（README）到达。
- source_of_truth 路径存在且被 Git 跟踪。

## 4. 写作风格

- 中文技术文档；表格优先；代码块标注语言；结论前置（先答案后论证）。
- 量化数字必须带条件（环境/时间/工具）或标注「未复测」——PickNear 简历数字教训。
- 引用源码必须带路径（类名 + 行号可选），禁止「某文件」模糊指代。
