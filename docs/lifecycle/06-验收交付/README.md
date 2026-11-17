---
title: 06-验收交付 文档索引
status: current
source_of_truth:
  - ../../../docs/lifecycle/06-验收交付/README.md
updated: 2026-11-16
---
# 06-验收交付 文档索引

**结论**：本目录承载验收交付阶段的**索引 + 模板骨架**；验收标准以 SLO 与门禁体系为唯一事实源，用户旅程以既有需求/设计文档为准，模板不复制正文。

## 1. 文档清单（索引）

| 文件 | 定位 | 唯一事实源（SSOT） | 责任代理 | 出口门禁 |
|---|---|---|---|---|
| [验收报告.md](验收报告.md) | G6 验收 + SLO-1~11 检查 + 旅程走查 + 人签 | [quality-attributes.md](../../quality/quality-attributes.md) / [review-system.md](../../quality/review-system.md) | tester+reviewer+人 | G6 |
| [用户操作手册.md](用户操作手册.md) | 用户旅程（浏览→抢约→Hold→支付→提醒→核销→评价；AI 分支；取消/候补） | [functional-requirements.md](../../quality/functional-requirements.md) / [frontend.md](../../standards/frontend.md) | designer+developer | G6 |
| [程序维护手册.md](程序维护手册.md) | 部署/架构概览链接、日常运维、on-call | [deployment/guide.md](../../deployment/guide.md) / [architecture/overview.md](../../architecture/overview.md) | developer | G5 |
| [项目开发总结报告.md](项目开发总结报告.md) | 交付物、门禁史、债务清单、经验反哺 | [roadmap.md](../../planning/roadmap.md) / [review-system.md](../../quality/review-system.md) | lead+各代理 | G6 后 |
| [产品交付清单.md](产品交付清单.md) | 制品清单（jar/镜像/SQL/配置/文档）+ 版本握手 | [git.md](../../standards/git.md) §4 / [deployment/guide.md](../../deployment/guide.md) | developer+reviewer | G5 |

## 2. 什么任务读什么文档（路由表）

| 任务 | 先读 | 再读本目录 |
|---|---|---|
| 执行 G6 验收 | [review-system.md](../../quality/review-system.md) §1 + [quality-attributes.md](../../quality/quality-attributes.md) | 验收报告.md |
| 编写用户说明/截图 | [functional-requirements.md](../../quality/functional-requirements.md) + [frontend.md](../../standards/frontend.md) | 用户操作手册.md |
| 交接运维/on-call | [deployment/guide.md](../../deployment/guide.md) + [运维手册](../08-安全与运维/运维手册.md) | 程序维护手册.md |
| 版本发布与制品清点 | [git.md](../../standards/git.md) §4 | 产品交付清单.md |
| 项目复盘 | [roadmap.md](../../planning/roadmap.md) + [decisions.md](../../architecture/decisions.md) | 项目开发总结报告.md |

## 3. 通用约束

- 模板规范遵循 [standards/doc.md](../../standards/doc.md) 与 [总索引](../README.md)：frontmatter 必填、表格优先、结论前置。
- 状态为「模板/待填」不得当作验收证据；G6 人签区必须真人签署，代理不得代签。
- 所有 SLO 数字须带条件（环境/时间/工具），否则标「未复测」；禁止编造达标结论。
- 各代理职责见 [agents/overview.md](../../agents/overview.md)。

—— 完 ——
