---
title: 08-安全与运维 文档索引
status: current
source_of_truth:
  - ../../../docs/lifecycle/08-安全与运维/README.md
updated: 2026-11-16
---
# 08-安全与运维 文档索引

**结论**：本目录承载安全与运维的**索引 + 模板骨架 + 检查表适用性映射**；安全口径唯一真值是 [security/guidelines.md](../../security/guidelines.md)，运维口径是 [deployment/guide.md](../../deployment/guide.md)，告警阈值是 [quality-attributes.md](../../quality/quality-attributes.md) §3。**传统主机/中间件检查表与等保材料只做外部参考，禁止编造适配结果或合规结论。**

## 1. 文档清单（索引）

| 文件 | 定位 | 唯一事实源（SSOT） | 责任代理 | 出口门禁 |
|---|---|---|---|---|
| [安全测试报告.md](安全测试报告.md) | 越权/注入/密钥/依赖漏洞结果 | [security/guidelines.md](../../security/guidelines.md) | tester | G4 安全审计 |
| [运维手册.md](运维手册.md) | 部署链接、日常检查、告警阈值、回滚 | [deployment/guide.md](../../deployment/guide.md) / [quality-attributes.md](../../quality/quality-attributes.md) §3 | developer | G5 |
| [安全检查表索引.md](安全检查表索引.md) | 源检查表 → NearSlot 组件适用性映射（索引 ONLY） | [总索引 §3.8](../README.md) | reviewer | 待适配 |
| [等级保护与合规说明.md](等级保护与合规说明.md) | 等保 2.0 映射说明 + 外部材料索引，标待适配 | 外部参考（不复制） | reviewer | 待适配 |

## 2. 什么任务读什么文档（路由表）

| 任务 | 先读 | 再读本目录 |
|---|---|---|
| 执行安全测试/审计 | [security/guidelines.md](../../security/guidelines.md) | 安全测试报告.md |
| 日常运维/告警处置/回滚 | [deployment/guide.md](../../deployment/guide.md) + quality-attributes §3 | 运维手册.md |
| 传统检查表能否复用 | [总索引 §3.8](../README.md) | 安全检查表索引.md |
| 等保/渗透/APP 检测参考 | 外部材料（仅索引） | 等级保护与合规说明.md |

## 3. 通用约束

- NearSlot 使用**容器 + 内嵌 Tomcat + Redis7/MySQL8/RabbitMQ + 云 WAF**，与源检查表的传统主机/中间件环境不同，一律标「待适配」，实测后再填结果。
- 模板规范遵循 [standards/doc.md](../../standards/doc.md) 与 [总索引](../README.md)：frontmatter 必填、表格优先、结论前置。
- 状态为「模板/待填」不得当作合规证据；任何「已符合等保」「0 风险」结论必须有实测证据与人签，否则禁止填写。
- 各代理职责见 [agents/overview.md](../../agents/overview.md)。

—— 完 ——
