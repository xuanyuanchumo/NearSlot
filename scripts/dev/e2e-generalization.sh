#!/usr/bin/env bash
B=http://localhost:8081
docker exec nearslot-redis redis-cli -a devredis123 SET "nearslot:slot:stock:3001" 5 >/dev/null 2>&1
AT=$(curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass12345"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null)
echo "=== 1) 时段（新字段 serviceStaffId）==="
curl -s "$B/api/v1/slots?shopId=1" --max-time 10 | head -c 200; echo
echo "=== 2) 抢约（Lua 新键 staff:timeline）==="
BOOK=$(curl -s -X POST $B/api/v1/appointments -H "Content-Type: application/json" -H "authorization: Bearer $AT" -d '{"slotId":3001}' --max-time 10)
echo "$BOOK" | head -c 120; echo
ORDER=$(echo "$BOOK" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data'] if d.get('code')==200 and d.get('data') else '')" 2>/dev/null)
echo "=== 3) 支付 ==="
curl -s -X POST $B/api/v1/payments/callback -H "Content-Type: application/json" -d "{\"orderId\":$ORDER,\"payNo\":\"GEN001\"}" --max-time 10 | head -c 100; echo
echo "=== 4) 榜单（新字段 serviceStaffId）==="
curl -s "$B/api/v1/tech-rank/1?date=$(date +%Y-%m-%d)" --max-time 10 | head -c 180; echo
echo "=== 5) Redis staff:timeline 键 ==="
docker exec nearslot-redis redis-cli -a devredis123 KEYS "nearslot:staff:timeline:*" 2>/dev/null | head -2
echo "=== 6) 订单 service_staff_id 落库 ==="
"D:/MySQL/MySQL Server 8.0/bin/mysql" -uroot -h127.0.0.1 -P3306 -N -e "SELECT CONCAT('orders=',COUNT(*),' staff_not_null=',SUM(service_staff_id IS NOT NULL)) FROM nearslot.appointment_order;" 2>/dev/null