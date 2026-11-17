#!/usr/bin/env bash
B=http://localhost:48081
echo "=== 改密踢出验证（找 newpass456 用户）==="
AT=$(curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"newpass456"}' | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['accessToken'] if d.get('code')==200 and d.get('data') else '')" 2>/dev/null)
echo "AT_LEN=${#AT}"
curl -s -X POST $B/api/v1/auth/password -H "Content-Type: application/json" -H "authorization: Bearer $AT" -d '{"oldPassword":"newpass456","newPassword":"pass123"}' --max-time 10 | head -c 90; echo
echo "=== 旧 AT 访问（应 401 + superseded）==="
curl -s -D - -o /dev/null "$B/api/v1/appointments" -H "authorization: Bearer $AT" --max-time 10 2>&1 | grep -E "HTTP/|X-Auth-Reason"
echo "=== 恢复后 pass123 可登录 ==="
curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass123"}' --max-time 10 | head -c 80; echo