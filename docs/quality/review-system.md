---
title: 全生命周期评审体系
status: current
source_of_truth:
  - ../../docs/quality/review-system.md
updated: 2026-09-05
---

# 全生命周期评审体系（七门禁 G0–G6）

## 1. 门禁总览

| 门禁 | 阶段 | 检查内容 | 通过标准 | 执行者 |
|---|---|---|---|---|
| G0 治理就绪 | Init | check_docs.py 可用 + 约束登记册/验收矩阵/门禁脚本已建 + 四代理配置齐全 | 治理脚本全绿，人必签 | lead+人 |
| G1 需求评审 | Plan | 功能性需求（functional-requirements.md）逐条可验收；用户旅程 6 步闭环 | 需求条目均有验收标准 | 代理组+人 |
| G2 设计评审 | Design | 架构符合 overview/ADR；新增决策补 ADR；接口/表设计过规范 | 无规范违例 | Reviewer |
| G3 代码评审 | Build | coding.md 全项 + 提交前自检 + 中文注释 + 依赖铁律 | Checklist 全绿 | Reviewer |
| G4 测试评审 | Build | 必测清单覆盖 + 覆盖率门禁 + Lua 语义测试 | 必测项全过 | Tester |
| G5 发布评审 | Deploy | 部署规范全项 + .env 完整 + 回滚方案 | 发布检查单全绿 | Reviewer+人 |
| G6 验收评审 | Maintain | SLO 达成 + 用户旅程实测 + 压测报告（带条件） | quality-attributes 指标达标 | 代理组+人 |

## 2. 五维检查单（每门禁通用）

1. 功能正确：主链路 + 边界（并发/重复/取消/超时）。
2. 安全：鉴权/越权/注入/密钥（security/guidelines.md § 索引）。
3. 性能：缓存键/TTL/线程池/SQL 索引（performance/guide.md §6 红线）。
4. 可维护：模块边界/命名/注释/文档同步（ADR 更新了吗）。
5. 可观测：日志级别/指标埋点/trace 白名单。

## 3. 评审流程

- PR 自检（Developer）→ 预审（Reviewer 代理，产出意见清单：blocker/major/minor）→ 修复回复 → 人抽检（G2/G5/G6 必有人参与）。
- 评审意见必须落到文件+行号；争议走 ADR 补录而非口头。
- 每次门禁结果记录到对应 PR/任务；连续两次同类 blocker 触发规范回补（更新 standards/）。

## 4. 文档与规范的反哺闭环

- 门禁发现的通用问题 → 更新 standards/ 或新增 ADR → check_docs.py 校验 → 团队同步。
- 里程碑门禁映射见学习.md §里程碑（Phase1–5 对应 G2–G6 逐级启用）。
