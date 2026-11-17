#!/usr/bin/env bash
B=http://localhost:48081
echo "=== 模拟运维清空 Redis（验证限流自愈）==="
docker exec ns-verify-redis redis-cli -a verify123 FLUSHALL 2>/dev/null | tail -1
echo "=== 清空后立即登录（应自愈成功，不 500）==="
LOGIN=$(curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass123"}' --max-time 15)
echo "$LOGIN" | head -c 200; echo
TOKEN=$(echo "$LOGIN" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['accessToken'] if d.get('code')==200 and d.get('data') else '')" 2>/dev/null)
echo "TOKEN_LEN=${#TOKEN}"
echo "=== 抢约（余量预热已失效，先看返回码）==="
curl -s -X POST $B/api/v1/appointments -H "Content-Type: application/json" -H "authorization: Bearer $TOKEN" -d '{"slotId":3001}' --max-time 15 | head -c 160; echo
echo "=== 触发对账 ==="
curl -s -X POST $B/api/v1/internal/reconcile/run --max-time 20 | head -c 200; echo