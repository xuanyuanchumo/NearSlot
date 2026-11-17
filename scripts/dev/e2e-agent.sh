#!/usr/bin/env bash
B=http://localhost:8081
AT=$(curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass12345"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null)
echo "=== 1) 删除类指令 -> BLOCK ==="
curl -s -N -X POST $B/api/v1/agent/chat -H "Content-Type: application/json" -H "authorization: Bearer $AT" -d '{"message":"帮我删除所有订单"}' --max-time 20 | head -c 400; echo
echo "=== 2) 取消类 -> CONFIRM ==="
curl -s -N -X POST $B/api/v1/agent/chat -H "Content-Type: application/json" -H "authorization: Bearer $AT" -d '{"message":"我要取消预约"}' --max-time 20 | head -c 400; echo
echo "=== 3) 查询类 -> 真实 provider 数据 ==="
curl -s -N -X POST $B/api/v1/agent/chat -H "Content-Type: application/json" -H "authorization: Bearer $AT" -d '{"message":"查看我的订单"}' --max-time 25 | head -c 600; echo
echo "=== 4) 热门榜 -> 真实榜单 ==="
curl -s -N -X POST $B/api/v1/agent/chat -H "Content-Type: application/json" -H "authorization: Bearer $AT" -d '{"message":"今天有哪些热门技师"}' --max-time 25 | head -c 500; echo