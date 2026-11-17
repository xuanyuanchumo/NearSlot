#!/usr/bin/env bash
echo "=== local redis-server ==="
which redis-server redis-cli 2>/dev/null || echo "NOT_IN_PATH"
echo "=== docker tcp ports ==="
docker ps --format "{{.Names}} {{.Ports}}" 2>&1 | head -3
echo "=== try run redis via docker cli (known works) ==="
docker run -d --name ns-verify-redis -p 46379:6379 redis:7-alpine --requirepass verify123 2>&1 | head -2
sleep 4
docker ps --filter "name=ns-verify-redis" --format "{{.Names}} {{.Status}}"
echo "=== ping via docker exec ==="
docker exec ns-verify-redis redis-cli -a verify123 ping 2>&1 | tail -1