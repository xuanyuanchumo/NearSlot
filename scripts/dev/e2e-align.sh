#!/usr/bin/env bash
B=http://localhost:48081
echo "=== 1) 连续错误密码 3 次（应累加失败计数）==="
for i in 1 2 3; do
  R=$(curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"wrong"}' --max-time 10)
  echo "  第 $i 次: $(echo $R | head -c 90)"
done
echo "=== 2) 正确密码（应清除失败计数并成功）==="
LOGIN=$(curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass123"}' --max-time 10)
echo "$LOGIN" | head -c 200; echo
TOKEN=$(echo "$LOGIN" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['accessToken'] if d.get('code')==200 and d.get('data') else '')" 2>/dev/null)
echo "=== 3) 候补三键：加入两次（幂等）==="
curl -s -X POST $B/api/v1/appointments/3001/waitlist -H "authorization: Bearer $TOKEN" --max-time 10 | head -c 120; echo
curl -s -X POST $B/api/v1/appointments/3001/waitlist -H "authorization: Bearer $TOKEN" --max-time 10 | head -c 120; echo
echo "=== 4) Redis 三键实况 ==="
docker exec ns-verify-redis redis-cli -a verify123 KEYS "nearslot:subscribe:*" 2>/dev/null | sort