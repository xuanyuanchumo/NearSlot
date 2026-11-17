#!/usr/bin/env bash
# C-SEC-01 密钥扫描骨架（G3/G5）
cd "$(dirname "$0")/../.." || exit 9
if command -v gitleaks >/dev/null 2>&1; then
  gitleaks detect --no-git --redact && exit 0 || exit 1
fi
echo "gitleaks 未安装，使用 grep 基础扫描兜底"
PATTERN='(password|secret|api[_-]?key|token)[[:space:]]*[:=][[:space:]]*[^$#[:space:]]{8,}'
MATCH=$(grep -rniE "$PATTERN" --include='*.yml' --include='*.yaml' --include='*.java' --include='*.properties' \
  --exclude-dir=target --exclude-dir=node_modules --exclude='.env.example' . | grep -v '\${' || true)
if [ -n "$MATCH" ]; then echo "疑似明文密钥："; echo "$MATCH"; exit 1; fi
echo "密钥基础扫描通过"
