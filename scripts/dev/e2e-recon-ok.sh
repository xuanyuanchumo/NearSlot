#!/usr/bin/env bash
B=http://localhost:48081
docker exec ns-verify-redis redis-cli -a verify123 SET "nearslot:slot:stock:3001" 5 >/dev/null 2>&1
LOGIN=$(curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass123"}' --max-time 10)
TOKEN=$(echo "$LOGIN" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['accessToken'] if d.get('code')==200 and d.get('data') else '')" 2>/dev/null)
echo "=== 抢约（应写对账日志）==="
curl -s -X POST $B/api/v1/appointments -H "Content-Type: application/json" -H "authorization: Bearer $TOKEN" -d '{"slotId":3001}' --max-time 10 | head -c 150; echo
sleep 2
echo "=== 对账（应 CONSISTENT）==="
curl -s -X POST $B/api/v1/internal/reconcile/run -H "X-Admin-Token: devadmintoken" --max-time 30 | head -c 120; echo
sleep 2