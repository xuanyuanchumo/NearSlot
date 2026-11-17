#!/usr/bin/env bash
# 重置本地开发环境：停止容器 + 删除数据卷 + 清理构建产物
set -euo pipefail
cd "$(dirname "$0")/../.." || exit 9
docker compose -f docker-compose.infra.yml down --volumes --remove-orphans
rm -rf nearslot-*/target nearslot-web/dist nearslot-web/node_modules/.vite
echo "已重置：容器/数据卷/构建产物全部清理"
