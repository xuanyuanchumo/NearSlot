#!/usr/bin/env bash
B=http://localhost:48081
echo "=== 登录并抢约 ==="
LOGIN=$(curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass123"}' --max-time 10)
TOKEN=$(echo "$LOGIN" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['accessToken'] if d.get('code')==200 and d.get('data') else '')" 2>/dev/null)
curl -s -X POST $B/api/v1/appointments -H "Content-Type: application/json" -H "authorization: Bearer $TOKEN" -d '{"slotId":3001}' --max-time 10 | head -c 150; echo
echo "=== 手动触发对账 ==="
curl -s -X POST $B/api/v1/internal/reconcile/run --max-time 20 | head -c 200; echo