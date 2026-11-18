#!/usr/bin/env bash
# 校验本次变更是否关联 backlog 编号（AGENTS.md §1）
#
# 为什么需要：本项目 20 项待办集中在 docs/planning/backlog.md，
# 若提交与待办脱钩，"做了但没人知道是哪条"会重演（历轮 audit 末尾的"未做"清单就这样散掉了）。
#
# 判定通过的条件（任一）：
#  1. 提交信息/分支名包含 BL-<域>-<序号>；
#  2. 本次改动包含 docs/planning/backlog.md（说明同步更新了清单）；
#  3. 显式设置 SKIP_BACKLOG_CHECK=1（用于 docs/CI 修复等确实无关的变更）。
set -uo pipefail

if [ "${SKIP_BACKLOG_CHECK:-0}" = "1" ]; then
  echo "[backlog-check] 已跳过（SKIP_BACKLOG_CHECK=1）"
  exit 0
fi

cd "$(dirname "$0")/../.." || exit 1

BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")
LAST_MSG=$(git log -1 --pretty=%s%n%b 2>/dev/null || echo "")
CHANGED=$(git diff --name-only HEAD 2>/dev/null; git diff --cached --name-only 2>/dev/null)

if echo "$BRANCH $LAST_MSG" | grep -qE 'BL-[A-Z]+-[0-9]+'; then
  echo "[backlog-check] OK：分支/提交信息已关联 backlog 编号"
  exit 0
fi
if echo "$CHANGED" | grep -q 'docs/planning/backlog.md'; then
  echo "[backlog-check] OK：本次变更同步更新了 backlog.md"
  exit 0
fi

echo "[backlog-check] 失败：未关联 backlog 编号"
echo "  请二选一："
echo "   1) 在分支名或提交信息里写明编号，如 feat(review): 评价闭环 (BL-REVIEW-01)"
echo "   2) 同步更新 NearSlot/docs/planning/backlog.md"
echo "   3) 确认与待办无关时：SKIP_BACKLOG_CHECK=1 git ..."
exit 1
