#!/usr/bin/env bash
echo "=== 启动 redis ==="
docker start ns-verify-redis >/dev/null 2>&1 || docker run -d --name ns-verify-redis -p 46379:6379 redis:7-alpine --requirepass verify123 >/dev/null 2>&1
sleep 4
docker ps --filter "name=ns-verify-redis" --format "{{.Status}}"
echo "=== 清理 Redis 残留 ==="
docker exec ns-verify-redis redis-cli -a verify123 FLUSHALL 2>/dev/null | tail -1