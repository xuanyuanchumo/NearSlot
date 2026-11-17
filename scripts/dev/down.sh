#!/usr/bin/env bash
# 停止本地开发中间件（保留数据卷）
set -euo pipefail
cd "$(dirname "$0")/../.." || exit 9
docker compose -f docker-compose.infra.yml down
echo "已停止（数据卷保留）。彻底清理加 --volumes：docker compose -f docker-compose.infra.yml down -v"
