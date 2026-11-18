#!/usr/bin/env bash
# 推送前自检（AGENTS.md §1/§4）—— 只做**机器能判定的部分**，不重复跑全量测试（CI 负责）
#
# 检查项：
#  1. 分支名符合 <type>/<scope>-<desc>（git.md §2）
#  2. 最近提交没有同时跨 3 个以上模块（AGENTS.md §4：一个提交只做一件事）
#  3. backlog 关联（复用 check-backlog-entry.sh）
#  4. 工作区无未提交改动（防止"我以为提交了"）
#
# 用法：bash scripts/dev/pre-push-check.sh
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1
fail=0

BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")
if echo "$BRANCH" | grep -qE '^(feat|fix|docs|refactor|perf|test|chore|ci)/[a-z0-9]+(-[a-z0-9]+)*$|^(master|main|develop)$'; then
  echo "[1/4] 分支名 OK：$BRANCH"
else
  echo "[1/4] 分支名不合规：$BRANCH"
  echo "      应为 <type>/<scope>-<desc>，如 feat/appointment-review"
  fail=1
fi

MODULES=$(git show --name-only --pretty=format: HEAD 2>/dev/null \
  | grep -oE '^nearslot-[a-z-]+' | sort -u | wc -l | tr -d ' ')
if [ "${MODULES:-0}" -gt 2 ]; then
  echo "[2/4] 警告：最近一次提交改动了 $MODULES 个模块，可能违反"一个提交只做一件事""
  echo "      如确属同一能力，请在 PR 描述里说明；否则请拆分"
else
  echo "[2/4] 提交粒度 OK（涉及 ${MODULES:-0} 个模块）"
fi

if bash scripts/dev/check-backlog-entry.sh >/dev/null 2>&1; then
  echo "[3/4] backlog 关联 OK"
else
  echo "[3/4] 未关联 backlog 编号（详见 scripts/dev/check-backlog-entry.sh）"
  fail=1
fi

if [ -z "$(git status --porcelain=v1 2>/dev/null)" ]; then
  echo "[4/4] 工作区干净"
else
  echo "[4/4] 存在未提交改动："
  git status --porcelain=v1 | head -5
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo ""
  echo "自检未通过。修复后重跑：bash scripts/dev/pre-push-check.sh"
  exit 1
fi
echo ""
echo "自检通过。可直接推送并开 PR（模板见 .github/PULL_REQUEST_TEMPLATE.md）。"
