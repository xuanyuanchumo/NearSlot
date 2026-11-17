---
title: Phase 5 详细开发计划（部署上线 / G5+G6）
status: current
source_of_truth:
  - ../../../docs/planning/phases/phase-5.md
updated: 2026-11-16
---

# Phase 5 详细开发计划：部署上线（W9 / G5+G6）

> 总纲：[development-plan.md](../development-plan.md) · 任务：[tasks.md](../tasks.md) §6 · 部署规范：[guide.md](../../deployment/guide.md) · 质量：[quality-attributes.md](../../quality/quality-attributes.md)

## 1. 目标与前置

- **目标**：可发布、可观测、可回滚、可验收——staging 全链通过并完成回滚演练。
- **前置**：G4 通过（覆盖率/压测/安全审计完成）。
- **出口**：G5 发布评审 + G6 验收评审（人签）；对账每日 0 差异；回滚演练成功。

## 2. 具体开发步骤（D→C→I）

| 步 | 环 | 动作 | 工件 | 验收 |
|---|---|---|---|---|
| 1 | D | 冻结发布检查单：镜像 tag/配置分层/.env/回滚方案/告警阈值 | 检查单（落 deployment/guide.md） | 每项可核对 |
| 2 | C | 镜像契约：多阶段 Dockerfile + 分层 COPY + 非 root + HEALTHCHECK | `Dockerfile` | 镜像无密钥；health 可用 |
| 3 | I | CI 构建推镜像：仅 push master / 手动；tag = latest + sha-xxxxxxx | `ci-cd.yml` build-image job | 禁本地 docker build 上生产 |
| 4 | I | 生产编排：compose/K8s + 配置分层（application→profile→env→启动参数） | 编排文件 | staging 全链路通过 |
| 5 | I | 监控告警：/actuator/prometheus + Langfuse OTLP（白名单）+ 对账 ABNORMAL 告警 | 告警规则 | 阈值符合 quality-attributes §3 |
| 6 | I | 对账定时任务上线：在途保护窗口 + 回补 + 告警 | 定时任务 | 每日 0 差异 |
| 7 | C/I | 回滚演练：镜像 tag 双版本共存，切 tag 回滚；DB 只向前修 | 演练报告 | 回滚成功并记录耗时 |
| 8 | V | G6 验收：11 项 SLO 实测 + 用户 journey 实测 + 人签 + 打 v{semver} tag | 验收报告 + tag | 全达标；人签完成 |

## 3. 落地方案

- **镜像**：多阶段 maven:3.9-temurin-21 builder（国内镜像 + go-offline + -T 1C）→ temurin:21-jre；先建 appuser(1000) 再 COPY --chown；layertools 拆层；`JAVA_OPTS=-XX:InitialRAMPercentage=50 -XX:MaxRAMPercentage=80`（guide.md §2）。
- **发布铁律**：`compose pull + up -d --no-build`；K8s 走 Argo 拉 tag（guide.md §5）。
- **回滚**：镜像 tag 双版本共存；数据库禁止生产 drop，只新迁移脚本向前修；回滚耗时记录入演练报告。
- **观测**：Prometheus 业务 counter（抢约成功/失败/回补、缓存命中、限流、Guard 拦截、消费延迟）+ Langfuse 白名单。

## 4. 闭环方案

| 环 | 验证动作 | 通过标准 |
|---|---|---|
| V-1 | staging 全链路 journey（含 AI 分支） | 全绿 |
| V-2 | 告警触发演练（制造 ABNORMAL>0） | 立即告警 |
| V-3 | 对账连续运行 3 日 | 每日 0 差异 |
| V-4 | 回滚演练 | 成功且耗时记录；服务恢复 |
| V-5 | G6 验收对照 SLO 表 | 11 项全达标，数字带条件 |
| F | 发布暴露问题 → ADR/规范反哺；复盘入档 | 文档与运维同步 |

## 5. DoD Checklist

- [ ] 镜像由 CI 构建推送，无本地 build 上生产
- [ ] staging 全链路通过；配置分层正确
- [ ] 监控告警齐全，白名单生效
- [ ] 对账每日 0 差异
- [ ] 回滚演练成功并记录耗时
- [ ] G5+G6 通过；人签；打 v{semver} tag

## 6. 风险与回退

| 风险 | 缓解 |
|---|---|
| 发布配置漂移 | .env 强校验 + 配置分层 + 发布检查单逐项核对 |
| 回滚失败 | 镜像 tag 双版本 + 演练前置 + DB 只向前修 |
| 对账误报 | 在途保护窗口（延迟阈值+2s，[09b §2](../../research/09b-hmdp-plus-verification.md)） |
| 告警风暴 | 白名单 + 阈值分级 + 抑制规则 |
