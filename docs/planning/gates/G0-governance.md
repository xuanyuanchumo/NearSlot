---
title: G0 治理就绪门禁记录
status: current
source_of_truth:
  - G0-governance.md
updated: 2026-11-16
---

# G0 治理就绪门禁记录

| 项 | 内容 |
|---|---|
| 阶段 | Phase 0（W0） |
| 门禁 | G0 治理就绪 |
| 结论 | **通过（自动检查项全绿，人签待办）** |
| 执行时间 | 2026-11-16 |

## 1. 证据（V-1~V-4）

| 验证 | 命令/工件 | 结果 |
|---|---|---|
| V-1 文档四检（正向） | \`python scripts/docs/check_docs.py docs --repo-root .\` | ✅ 92 文档 / 11 索引 / 92 current 全绿 |
| V-1 文档四检（负向） | \`python scripts/docs/test_check_docs.py\` | ✅ C1/C2/C3/C4 四个负向用例均被正确拦截 |
| V-4 密钥扫描 | \`bash scripts/security/scan_secrets.sh\` | ✅ 通过（gitleaks 未装，grep 兜底；CI 应装 gitleaks） |
| CI YAML | PyYAML 解析 | ✅ 五 job（changes/docs/ci/integration/build-image/notify）语法正确 |
| pre-commit | \`.husky/pre-commit\` 调用 check_docs | ✅ 已配置 |

## 2. 交付工件清单

| 工件 | 路径 |
|---|---|
| check_docs.py（六检） | scripts/docs/check_docs.py |
| 单元负向测试 | scripts/docs/test_check_docs.py |
| 负向样例 | tests/fixtures/docs_negative/ |
| 约束登记册 | docs/planning/constraints.md |
| GWT 验收矩阵 | docs/planning/acceptance-matrix.md |
| ArchUnit 契约 | tests/architecture/ArchitectureRulesTest.java（Phase 1 启用） |
| commitlint + husky | commitlint.config.js / .husky/ |
| CI 五 job 骨架 | .github/workflows/ci-cd.yml |
| 密钥模板 + 扫描 | .env.example / scripts/security/scan_secrets.sh |
| reviewer README 纠偏 | agents/reviewer/README.md（"已实现"→实际路径与证据） |

## 3. 本次门禁发现并修复的漂移（F 环）

| 发现 | 修复 |
|---|---|
| research/10 内部链接深度 \`../../\` 多一层（30 处） | 统一为 \`../\` |
| roadmap.md 指向 research/ 的 7 处链接缺 \`../\` | 已补 |
| docs/README.md / research/12 的 source_of_truth 路径不符文件相对约定 | 已纠偏 |
| reviewer README 声称脚本已实现却无文件 | 已改为实际路径+验证证据 |

## 4. 待办（有条件通过项，非 Blocker）

- [ ] 人签确认 G0（HITL 点，需负责人签字）。
- [ ] CI 环境安装 gitleaks 替换 grep 兜底。
- [ ] Phase 1 落地 ArchUnit 依赖（当前测试类以 assumeTrue 跳过，防误绿）。
- [ ] 版本四端同步脚本（C-DOC-04）纳入 CI。

## 5. Blocker

无。所有自动检查项通过，4 项漂移已修复并复验。
