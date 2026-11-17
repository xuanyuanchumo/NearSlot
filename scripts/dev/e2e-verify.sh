#!/usr/bin/env bash
B=http://localhost:48081
echo "=== 1) 登录 ==="
LOGIN=$(curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass123"}' --max-time 15)
echo "$LOGIN" | head -c 400; echo
TOKEN=$(echo "$LOGIN" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['accessToken'] if d.get('code')==200 and d.get('data') else '')" 2>/dev/null)
echo "TOKEN_LEN=${#TOKEN}"
echo "=== 2) 抢约 ==="
BOOK=$(curl -s -X POST $B/api/v1/appointments -H "Content-Type: application/json" -H "authorization: Bearer $TOKEN" -d '{"slotId":3001}' --max-time 15)
echo "$BOOK" | head -c 300; echo
ORDER=$(echo "$BOOK" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data'] if d.get('code')==200 and d.get('data') else '')" 2>/dev/null)
echo "ORDER=$ORDER"
echo "=== 3) 重复抢约（期望 50102/10006）==="
curl -s -X POST $B/api/v1/appointments -H "Content-Type: application/json" -H "authorization: Bearer $TOKEN" -d '{"slotId":3001}' --max-time 15 | head -c 250; echo
echo "=== 4) 取消 ==="
curl -s -X POST $B/api/v1/appointments/$ORDER/cancel -H "authorization: Bearer $TOKEN" --max-time 15 | head -c 250; echo