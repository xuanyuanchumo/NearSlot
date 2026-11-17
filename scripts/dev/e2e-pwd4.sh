#!/usr/bin/env bash
B=http://localhost:48081
AT=$(curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"tmp88888"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null)
echo "=== 恢复为 pass12345（8位）==="
curl -s -X POST $B/api/v1/auth/password -H "Content-Type: application/json" -H "authorization: Bearer $AT" -d '{"oldPassword":"tmp88888","newPassword":"pass12345"}' --max-time 10 | head -c 90; echo
echo "=== 验证 pass12345 可登录 ==="
curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass12345"}' --max-time 10 | head -c 80; echo