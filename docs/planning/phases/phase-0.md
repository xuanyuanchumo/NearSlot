---
title: Phase 0 详细开发计划（治理就绪 / G0）
status: current
source_of_truth:
  - ../../../docs/planning/phases/phase-0.md
updated: 2026-11-16
---

# Phase 0 详细开发计划：治理就绪（W0 / G0）

> 总纲：[development-plan.md](../development-plan.md) DCIVF 模型 · 任务：[tasks.md](../tasks.md) §1 · 研究依据：[12 质量治理深研](../../research/12-quality-governance-deep.md)

## 1. 目标与前置

- **目标**：把"纸面规范"变成"可执行门禁"，消除 [12 §0](../../research/12-quality-governance-deep.md) 判定的"脚本/CI/代码执行载体全部缺位"。
- **前置**：研究报告 00–12 已完成（✅）；四代理配置齐全（✅）。
- **出口**：G0 治理就绪——四条确定性检查在负向用例下全部拦截，人签。

## 2. 具体开发步骤（D→C→I）

| 步 | 环 | 动作 | 工件 | 验收 |
|---|---|---|---|---|
| 1 | D | 冻结四检规则：frontmatter 必填/链接可达/current 可从 README 到达/source_of_truth 存在 | 规则清单（写入 check_docs.py 头注释） | 四检有明确判据 |
| 2 | C | 先造 4 类负向样例文档（故意缺字段/坏链接/不可达/假事实源） | `tests/fixtures/docs_negative/*` | 样例存在且各命中一检 |
| 3 | I | 实现 check_docs.py（argparse 收目录，逐项检查，非零退出码） | `scripts/docs/check_docs.py` | 正向全绿、负向全红 |
| 4 | D | 列约束登记册：依赖铁律/编码红线/密钥规则/返回码单一枚举 | `docs/planning/constraints.md` | 每条可机器检测 |
| 5 | C/I | 写 ArchUnit 规则 + 契约：module 禁互依、framework 禁 import module、返回码枚举唯一 | ArchUnit 测试类 | 违规样例被检测 |
| 6 | D/C | 建 GWT 验收矩阵：FR-1~5 每条 + SLO-1~11 每条 | `docs/planning/acceptance-matrix.md` | 无孤儿需求/指标 |
| 7 | I | commitlint + husky + CI 五 job 骨架（changes/docs/ci/integration/build-image） | `.husky/` · `ci-cd.yml` | 非法 commit 被拒；job 可跑 |
| 8 | I | .env.example + 密钥扫描；修正 reviewer README 漂移 | `.env.example` · 扫描配置 · `agents/reviewer/README.md` | compose 无明文；README 与事实一致 |

## 3. 落地方案

- **check_docs.py**：Python 标准库（`pathlib`+`re`+`yaml`），四函数 `check_frontmatter/check_links/check_reachability/check_source`；`main()` 汇总退出码；供 CI 与本地 `check_all.sh` 复用。
- **约束登记册**：表格 `约束ID | 描述 | 检测手段 | 违反示例`；AGENTS.md 只保留指针，细则落本册（防双源）。
- **GWT 矩阵**：`编号 | Given | When | Then | 对应 SLO/FR | 验证脚本`；验收脚本基准来自 [functional-requirements.md §3](../../quality/functional-requirements.md)。
- **CI 骨架**：纯 `*.md` 变更跳过编译只跑 docs 检查（deployment/guide.md §4）；集成测试用 `-DexcludedGroups=integration`。

## 4. 闭环方案

| 环 | 验证动作 | 通过标准 |
|---|---|---|
| V-1 | 负向样例逐一跑 check_docs.py | 4 类样例全部非零退出 |
| V-2 | ArchUnit 对故意违规的样例模块运行 | 违规被检测并报错 |
| V-3 | 本地提交非法 commit message | husky 拒绝 |
| V-4 | CI 五 job 各跑一次（含纯文档 PR 跳过编译） | 行为符合 deployment/guide.md §4 |
| F | 发现规范本身模糊 → 修 standards/doc.md 或新增 ADR；脚本同步 | 规范与脚本一致，check_docs 绿 |

## 5. DoD Checklist

- [ ] 四检正向绿、负向全红
- [ ] 约束册每条有检测手段
- [ ] FR-1~5 与 SLO-1~11 全进 GWT 矩阵
- [ ] CI 五骨架 job 可跑；非法 commit 被拒
- [ ] .env.example 齐全；无明文密钥
- [ ] reviewer README 不再声称脚本"已实现"却无文件
- [ ] G0 人签，门禁记录归档

## 6. 风险与回退

| 风险 | 缓解 |
|---|---|
| check_docs 误报阻断正常 PR | 先用现有 22 文档跑基线，白名单例外需登记 |
| 约束过多拖慢开发 | 首版只固化"可机器检测"的铁律，其余进评审清单 |
| G0 变成纸面仪式 | 负向用例是硬门槛，无负向证据不得人签 |
