#!/usr/bin/env bash
B=http://localhost:48081
echo "=== 1) 验证码登录（先取码）==="
CODE=$(curl -s -X POST "$B/api/v1/auth/code?phone=13800138000" --max-time 15 | python -c "import sys,json;print(json.load(sys.stdin).get('data',''))" 2>/dev/null)
echo "CODE=$CODE"
LOGIN2=$(curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d "{\"phone\":\"13800138000\",\"code\":\"$CODE\",\"type\":\"code\"}" --max-time 15)
echo "$LOGIN2" | head -c 250; echo
echo "=== 2) 密码登录（BCrypt 自动升级路径）==="
LOGIN=$(curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass123"}' --max-time 15)
echo "$LOGIN" | head -c 250; echo
TOKEN=$(echo "$LOGIN" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['accessToken'] if d.get('code')==200 and d.get('data') else '')" 2>/dev/null)
echo "=== 3) 加入候补 ==="
curl -s -X POST $B/api/v1/appointments/3001/waitlist -H "authorization: Bearer $TOKEN" --max-time 15 | head -c 200; echo
echo "=== 4) 重复加入候补（幂等）==="
curl -s -X POST $B/api/v1/appointments/3001/waitlist -H "authorization: Bearer $TOKEN" --max-time 15 | head -c 200; echo