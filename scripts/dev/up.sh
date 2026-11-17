#!/usr/bin/env bash
# 启动本地开发中间件（幂等）
# 镜像缺失时会提示从华为云 SWR 拉取（见 docs/deployment/docker-mirror.md）
set -euo pipefail
cd "$(dirname "$0")/../.." || exit 9
[ -f .env ] || { echo "缺少 .env，请先 cp .env.example .env 并填写"; exit 1; }

need() { docker image inspect "$1" >/dev/null 2>&1; }
MISSING=""
for img in mysql:8.0 redis:7-alpine rabbitmq:3.13-management-alpine; do
  if ! need "$img"; then MISSING="$MISSING $img"; fi
done

if [ -n "$MISSING" ]; then
  echo "⚠️  本地缺少镜像:$MISSING"
  echo "   国内建议用华为云 SWR 拉取（Docker Hub 直连极慢）："
  echo
  for img in $MISSING; do
    case "$img" in
      mysql:8.0)        src="mysql:8.0" ;;
      redis:7-alpine)   src="redis:7-alpine" ;;
      rabbitmq:3.13-management-alpine) src="rabbitmq:3.13-management-alpine" ;;
      *) src="$img" ;;
    esac
    echo "   docker pull swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/$src"
    echo "   docker tag  swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/$src $img"
    echo
  done
  echo "   详见 docs/deployment/docker-mirror.md"
  exit 1
fi

docker compose -f docker-compose.infra.yml up -d
echo "等待健康检查..."
docker compose -f docker-compose.infra.yml ps
echo
echo "中间件端口：MySQL 43306 / Redis 46379 / RabbitMQ 45672（控制台 45673）"
echo "后端本地启动：mvn -pl nearslot-app spring-boot:run -Dspring-boot.run.profiles=dev"
echo "前端本地启动：cd nearslot-web && vp dev"
