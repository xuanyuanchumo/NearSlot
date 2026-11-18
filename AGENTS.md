# AGENTS.md — AI 开发者行为契约

> 本文件是**契约**，不是建议。每条规则都绑定一个**可执行的强制点**（脚本/CI/测试）；
> 没有强制点的条目一律不得写入本文件 —— 本项目已三次出现"文档写了但没人执行"的假门禁：
> · CI 只**打印**覆盖率却从不因低于 80% 失败；
> · ArchUnit 规则放在只看得到 common 的模块里，"通过"时其实一条类都没检查；
> · `sql/03~05` 标题写着"幂等"，实际在空库重放时 `ERROR 1060 Duplicate column`。
>
> 因此：**能自动化的写进 CI，不能自动化的写进 PR 模板自检项**，两者都不做的不要写。

## 1. 开工前（必须）

| 规则 | 强制点 |
|---|---|
| 先读 `docs/指导核心.md`、`docs/standards/git.md`、`docs/planning/backlog.md` | PR 自检第 1 项 |
| 确认本次任务在 backlog 有编号，或新增一条编号 | `scripts/dev/check-backlog-entry.sh` |
| 一次任务只开一个分支，分支名 `<type>/<scope>-<desc>` | `scripts/dev/pre-push-check.sh` 校验分支名 |

## 2. 写代码时（必须）

| 规则 | 强制点 |
|---|---|
| 业务策略值不得写成 `static final` 常量（ADR-030），走 `@ConfigurationProperties` | Code Review + `docs/standards/coding.md` |
| 模块间不得直接依赖，跨域走 `common/api` 契约（C-ARCH-01） | `ArchitectureRulesTest`（在 `nearslot-app`，能看到全部模块） |
| MyBatis-Plus 的 lambda wrapper 只用于**有 Spring 上下文**的路径；能被单测覆盖的服务用字符串列名 | 单测能跑通即证明 |
| 文件写入不得拼接用户提供的路径/文件名（ADR-031） | `LocalFileStorageTest` |
| 新增表/列必须写**幂等**迁移脚本（`information_schema` 守卫），文件名两位零填充 | 空库重放 12 个脚本零报错 |

## 3. 验证时（必须，禁止伪造）

| 规则 | 强制点 |
|---|---|
| 构建必须 clean：`bash scripts/dev/build.sh`（增量编译曾多次导致"改了没生效"） | 该脚本本身强制 clean |
| 涉及 Redis/MySQL 的行为，必须在**真实中间件**（Docker）上跑过，不能只用 mock | E2E 脚本落在 `scripts/dev/verify_*.sh` |
| 覆盖率 ≥ 80% | CI `build` job 阈值失败（见 `ci-cd.yml`） |
| 文档门禁通过 | `.husky/pre-commit` → `scripts/docs/check_docs.py`（docs 为独立仓库，不进代码仓 CI） |

**禁止行为**：
- ❌ 声称"已验证"但只跑过单测/mock；
- ❌ 把"应该没问题"写进报告当作结论；
- ❌ 用 `/tmp` 存 E2E 中间文件（MSYS 下多次读不到，曾导致误判）；
- ❌ 绕过沙箱限制或审批（本次会话审批已禁用，任何降级都属违规）。

## 4. 提交时（必须）

| 规则 | 强制点 |
|---|---|
| 一个提交只做一件事；跨域变更必须拆分 | PR 自检 + `pre-push` 提示多模块改动 |
| commit 格式 `<type>(<scope>): <subject>` | husky + commitlint |
| 文档与代码**同批提交**：`docs/` 是独立私有仓库，须同时提交两侧并互相引用同一 backlog 编号 | PR 自检第 5 项 |
| 完成后更新 `docs/planning/backlog.md` 状态与完成记录 | PR 自检第 6 项 |

## 5. 收尾时（必须）

| 规则 | 强制点 |
|---|---|
| 未完成项**必须**登记到 backlog（不允许只写在某轮 audit 末尾） | PR 自检第 6 项 |
| 新增架构决策必须有 ADR（`docs/architecture/decisions.md`） | PR 自检第 4 项 |
| 合并用 Squash，保留语义化标题 | `git.md` §2 |

## 6. 仓库拓扑（容易搞错）

- **代码仓**：`xuanyuanchumo/NearSlot`（私有），根 = `NearSlot/`，`docs/` 被 gitignore。
- **文档仓**：`xuanyuanchumo/NearSlot-docs`（私有），根 = `NearSlot/docs/`。
- 两侧是两个独立 git 仓库，**不要**试图把 docs 合并进代码仓，也不要公开文档仓。
- CI 只跑代码仓；文档门禁由 `.husky/pre-commit` 本地执行（文档仓独立 CI 见 BL-PROC-06）。

## 7. 已知环境约束（踩过的坑，不要再踩）

- **MSYS `/tmp` 不可靠**：跨多次 bash 调用会读不到文件 → 一律用工作区内临时目录。
- **增量编译会骗人**：编辑工具改写文件后 mtime 可能不前进，Maven 判定 class 新鲜而跳过编译 → 用 `scripts/dev/build.sh`。
- **Docker 中间件端口**：MySQL 43306 / Redis 46379 / RabbitMQ 45672（`docker-compose.infra.yml` 是唯一事实源）。
- **E2E 前置清理**：抢约类测试必须清 `nearslot:slot:*` **和** `nearslot:staff:timeline:*`，否则上轮占用的技师时间轴会让本轮恒判"时段已满"(50103)。
- **纯单测下 lambda 不可用**：`can not find lambda cache` → 服务层用字符串列名 wrapper。

## 8. 与本文件冲突时

以 `docs/指导核心.md` 为准；若二者不一致，本次任务必须先修文档再改代码（不允许"代码先行、文档后补"）。
