#!/usr/bin/env bash
# 查看本地开发环境状态
set -euo pipefail
cd "$(dirname "$0")/../.." || exit 9
echo "=== 中间件容器 ==="
docker compose -f docker-compose.infra.yml ps 2>/dev/null || echo "未启动"
echo
echo "=== 端口探测 ==="
for p in 43306 46379 45672 45673; do
  if (echo > /dev/tcp/127.0.0.1/$p) 2>/dev/null; then echo "  $p 已监听"; else echo "  $p 未监听"; fi
done
