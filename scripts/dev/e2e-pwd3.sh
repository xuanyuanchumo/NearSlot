#!/usr/bin/env bash
B=http://localhost:48081
echo "=== 1) 用当前密码 pass123 登录 ==="
AT=$(curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass123"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null)
echo "AT_LEN=${#AT}"
echo "=== 2) 改密 pass123 -> tmp88888 ==="
curl -s -X POST $B/api/v1/auth/password -H "Content-Type: application/json" -H "authorization: Bearer $AT" -d '{"oldPassword":"pass123","newPassword":"tmp88888"}' --max-time 10 | head -c 90; echo
echo "=== 3) 旧 AT 访问（期望 401 + superseded）==="
curl -s -D - -o /dev/null "$B/api/v1/appointments" -H "authorization: Bearer $AT" --max-time 10 2>&1 | grep -E "HTTP/|X-Auth-Reason"
echo "=== 4) 恢复正常密码 ==="
AT2=$(curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"tmp88888"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null)
curl -s -X POST $B/api/v1/auth/password -H "Content-Type: application/json" -H "authorization: Bearer $AT2" -d '{"oldPassword":"tmp88888","newPassword":"pass123"}' --max-time 10 | head -c 90; echo