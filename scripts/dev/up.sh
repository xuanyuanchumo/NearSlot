#!/usr/bin/env bash
# 启动本地开发中间件（幂等）
set -euo pipefail
cd "$(dirname "$0")/../.." || exit 9
[ -f .env ] || { echo "缺少 .env，请先 cp .env.example .env 并填写"; exit 1; }
docker compose -f docker-compose.infra.yml up -d
echo "等待健康检查..."
docker compose -f docker-compose.infra.yml ps
echo
echo "中间件端口：MySQL 43306 / Redis 46379 / RabbitMQ 45672（控制台 45673）"
echo "后端本地启动：mvn -pl nearslot-app spring-boot:run -Dspring-boot.run.profiles=dev"
echo "前端本地启动：cd nearslot-web && vp dev"
