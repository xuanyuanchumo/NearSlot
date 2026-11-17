---
title: 04-开发阶段 文档索引
status: current
source_of_truth:
  - ../../../docs/lifecycle/04-开发阶段/README.md
updated: 2026-11-16
---
# 04-开发阶段 文档索引

**结论**：本目录只承载开发阶段**索引 + 模板骨架**；开发流程、编码、Git、评审的唯一事实源全部在 `docs/` 既有文档，模板只做相对链接，不复制正文。

## 1. 文档清单（索引）

| 文件 | 定位 | 唯一事实源（SSOT） | 责任代理 | 出口门禁 |
|---|---|---|---|---|
| [软件开发文档.md](软件开发文档.md) | DCIVF 开发工作流 + 分支/提交 + PR 字段 | [development-plan.md](../../planning/development-plan.md) / [git.md](../../standards/git.md) | developer | G3 代码评审 |
| [编码实现说明.md](编码实现说明.md) | 中文注释、返回码枚举单一来源、Lua/Java 共享码表 | [coding.md](../../standards/coding.md) / [research/10](../../research/10-backend-engineering-deep.md) | developer | G3 代码评审 |
| [开发进度月报.md](开发进度月报.md) | W1–W9 进度、门禁达成、阻塞与下一步 | [development-plan.md](../../planning/development-plan.md) | developer | 阶段末复盘 |
| [软件问题报告.md](软件问题报告.md) | 开发中问题登记（正式测试缺陷见 05 阶段） | [../05-测试阶段/BUG描述报告书.md](../05-测试阶段/BUG描述报告书.md) | developer→tester | G3/G4 |
| [软件修改报告.md](软件修改报告.md) | 变更请求：原因/影响面/ADR/证据/回滚 | [review-system.md](../../quality/review-system.md) / [decisions.md](../../architecture/decisions.md) | developer→reviewer | G3/G5 |

## 2. 什么任务读什么文档（路由表）

| 任务 | 先读 | 再读本目录 |
|---|---|---|
| 认领任务、建分支、提 PR | [git.md](../../standards/git.md) | 软件开发文档.md |
| 写代码/加注释/定义返回码 | [coding.md](../../standards/coding.md) | 编码实现说明.md |
| 汇报周进度/阻塞 | [tasks.md](../../planning/tasks.md) | 开发进度月报.md |
| 记录开发中问题 | BUG描述报告书（05 阶段） | 软件问题报告.md |
| 提出范围/设计变更 | [decisions.md](../../architecture/decisions.md) | 软件修改报告.md |

## 3. 通用约束

- 模板规范见 [standards/doc.md](../../standards/doc.md) 与 [总索引](../README.md)：frontmatter 必填、表格优先、结论前置、一主题一 current。
- 状态为「模板/待填」时不得当作已交付证据；填写后必须关联 FR/ADR 编号并过对应门禁。
- 禁止把 `docs/` 既有规范正文复制进本目录；发现内容冲突以 `docs/` 为准并触发 F 环反哺。
- 责任代理定义见 [agents/overview.md](../../agents/overview.md)，开发者约束见 [coding.md](../../standards/coding.md) 与 [git.md](../../standards/git.md)。

—— 完 ——
