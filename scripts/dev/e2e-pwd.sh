#!/usr/bin/env bash
B=http://localhost:48081
echo "=== 1) 登录拿 AT ==="
AT=$(curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass123"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null)
echo "AT_LEN=${#AT}"
echo "=== 2) 改密前：旧 AT 可访问 ==="
curl -s -o /dev/null -w "HTTP=%{http_code}\n" "$B/api/v1/appointments" -H "authorization: Bearer $AT" --max-time 10
echo "=== 3) 改密 ==="
curl -s -X POST $B/api/v1/auth/password -H "Content-Type: application/json" -H "authorization: Bearer $AT" -d '{"oldPassword":"pass123","newPassword":"newpass456"}' --max-time 10 | head -c 100; echo
echo "=== 4) 改密后：旧 AT 应 401 + X-Auth-Reason ==="
curl -s -D - -o /dev/null "$B/api/v1/appointments" -H "authorization: Bearer $AT" --max-time 10 2>&1 | grep -E "HTTP/|X-Auth-Reason"
echo "=== 5) 新密码登录 ==="
curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"newpass456"}' --max-time 10 | head -c 90; echo