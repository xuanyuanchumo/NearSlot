#!/usr/bin/env bash
echo "=== 容器网关 ==="
docker exec ns-prom-test ip route 2>/dev/null | head -3
echo "=== 候选地址测试 ==="
for host in host.docker.internal 172.17.0.1 192.168.65.1 10.0.2.2; do
  code=$(docker exec ns-prom-test sh -c "wget -qO- --timeout=3 http://$host:8081/api/v1/system/ping 2>/dev/null | head -c 20" 2>/dev/null)
  if [ -n "$code" ]; then echo "$host -> OK: $code"; else echo "$host -> FAIL"; fi
done