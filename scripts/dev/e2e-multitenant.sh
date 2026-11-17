#!/usr/bin/env bash
B=http://localhost:8081
echo "=== 1) 用消费者 1001 登录（注意：种子里是 PLATFORM）==="
P=$(curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass12345"}' | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['accessToken'])" 2>/dev/null)
echo "PLATFORM token len=${#P}"
echo "=== 2) PLATFORM 创建服务项（应成功 200）==="
curl -s -X POST $B/api/v1/admin/service-items -H "Content-Type: application/json" -H "authorization: Bearer $P" -d '{"shopId":1,"name":"测试项目","durationMin":30,"priceCent":9900}' --max-time 10 | head -c 150; echo
echo "=== 3) 商家 2001 登录 ==="
M=$(curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d '{"phone":"13900139000","password":"pass12345"}' | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['accessToken'])" 2>/dev/null)
echo "MERCHANT token len=${#M}"
echo "=== 4) MERCHANT 操作自己店铺（shopId=1，应成功）==="
curl -s -X POST $B/api/v1/admin/service-items -H "Content-Type: application/json" -H "authorization: Bearer $M" -d '{"shopId":1,"name":"商家自建项目","durationMin":45,"priceCent":12800}' --max-time 10 | head -c 150; echo
echo "=== 5) MERCHANT 操作他人店铺（shopId=2，应 403）==="
curl -s -X POST $B/api/v1/admin/service-items -H "Content-Type: application/json" -H "authorization: Bearer $M" -d '{"shopId":2,"name":"越权项目","durationMin":30,"priceCent":100}' --max-time 10 -w "
HTTP=%{http_code}
" | head -c 200