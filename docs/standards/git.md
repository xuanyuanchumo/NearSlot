---
title: Git 与协作规范
status: current
source_of_truth:
  - ../../docs/standards/git.md
updated: 2026-09-05
---

# Git 与协作规范（继承 PickNear 全套）

## 1. Commit（commitlint + husky 强制）

- 格式：<type>(<scope>): <subject>
- type 枚举：feat/fix/docs/style/refactor/perf/test/chore/ci/revert。
- scope 表：interceptor/service/controller/mapper/entity/utils/config/cache/lua/security/dto/common/auth/shop/appointment/order/agent/framework/docs。
- subject：中文 ≤50 字，祈使句，不加句号；body 每行 ≤72 字符，说明 What+Why；footer：Refs/Closes/BREAKING CHANGE。

## 2. 分支

- 命名：<type>/<scope>-<desc>（kebab-case），如 feat/appointment-lua-seckill。
- main（保护，CI 必须绿）+ develop（集成分支）+ feature/fix 分支；hotfix 从 main 拉出回 main+develop。
- 合并：Squash Merge 为主，保留语义化标题；禁 force push 共享分支。

## 3. PR 规则

- PR 描述模板：背景 / 变更点 / 自检清单 / 测试证据 / 影响面（含性能敏感声明，见 performance/guide.md §6）。
- 至少 1 名 Reviewer（Reviewer 代理产出预审意见）；文档与代码同 PR（ADR 新增/更新必须附决策记录）。

## 4. 标签与版本

- Maven revision + flatten-maven-plugin（hmdp-plus 模式）统一版本；发布打 v{semver} tag。
- CHANGELOG.md 按版本记录 feat/fix/breaking；BREAKING CHANGE 必须升级 API 版本路径。
