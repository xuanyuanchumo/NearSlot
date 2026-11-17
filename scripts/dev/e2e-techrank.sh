#!/usr/bin/env bash
B=http://localhost:8081
docker exec nearslot-redis redis-cli -a devredis123 DEL "nearslot:slot:stock:3001" >/dev/null 2>&1
docker exec nearslot-redis redis-cli -a devredis123 SET "nearslot:slot:stock:3001" 5 >/dev/null 2>&1
AT=$(curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass12345"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null)
echo "=== 1) 抢约 ==="
BOOK=$(curl -s -X POST $B/api/v1/appointments -H "Content-Type: application/json" -H "authorization: Bearer $AT" -d '{"slotId":3001}' --max-time 10)
echo "$BOOK" | head -c 120; echo
ORDER=$(echo "$BOOK" | python -c "import sys,json;print(json.load(sys.stdin)['data'] if json.load(open('/dev/stdin')) else '')" 2>/dev/null)
ORDER=$(echo "$BOOK" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data'] if d.get('code')==200 and d.get('data') else '')" 2>/dev/null)
echo "ORDER=$ORDER"
echo "=== 2) 支付 ==="
curl -s -X POST $B/api/v1/payments/callback -H "Content-Type: application/json" -d "{\"orderId\":$ORDER,\"payNo\":\"RANK001\"}" --max-time 10 | head -c 120; echo
echo "=== 3) 技师榜（应含 techId 2001，deals>=1）==="
curl -s "$B/api/v1/tech-rank/1?date=$(date +%Y-%m-%d)&limit=10" --max-time 10 | head -c 250; echo
echo "=== 4) Redis ZSet 实况 ==="
docker exec nearslot-redis redis-cli -a devredis123 ZRANGE "nearslot:tech:rank:1:$(date +%Y%m%d)" 0 -1 WITHSCORES 2>/dev/null | tr '\n' ' ' | head -c 200