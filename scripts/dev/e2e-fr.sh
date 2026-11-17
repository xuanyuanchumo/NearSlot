#!/usr/bin/env bash
B=http://localhost:48081
echo "=== 登录（拿 AT + RT）==="
LOGIN=$(curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass123"}' --max-time 10)
AT=$(echo "$LOGIN" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['accessToken'] if d.get('code')==200 and d.get('data') else '')" 2>/dev/null)
echo "AT_LEN=${#AT}"
RT=$(docker exec ns-verify-redis redis-cli -a verify123 GET "nearslot:login:token:refresh:1001" 2>/dev/null | tr -d "\r")
echo "RT=$RT"
echo "=== FR-1.3 刷新（应返回新 AT）==="
NEWAT=$(curl -s -X POST $B/api/v1/auth/refresh -H "Refresh-Token: $RT" --max-time 10)
echo "$NEWAT" | head -c 180; echo
echo "=== FR-1.4 等级闸（时段3002需 Lv8，用户 Lv5 应拒绝）==="
curl -s -X POST $B/api/v1/appointments -H "Content-Type: application/json" -H "authorization: Bearer $AT" -d '{"slotId":3002}' --max-time 10 | head -c 180; echo
echo "=== FR-4.2 支付回调 ==="
BOOK=$(curl -s -X POST $B/api/v1/appointments -H "Content-Type: application/json" -H "authorization: Bearer $AT" -d '{"slotId":3001}' --max-time 10)
echo "下单: $(echo $BOOK | head -c 100)"
ORDER=$(echo "$BOOK" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data'] if d.get('code')==200 and d.get('data') else '')" 2>/dev/null)
curl -s -X POST $B/api/v1/payments/callback -H "Content-Type: application/json" -d "{\"orderId\":$ORDER,\"payNo\":\"PAY001\"}" --max-time 10 | head -c 150; echo
echo "=== FR-4.2 重复回调（应 IDEMPOTENT）==="
curl -s -X POST $B/api/v1/payments/callback -H "Content-Type: application/json" -d "{\"orderId\":$ORDER,\"payNo\":\"PAY001\"}" --max-time 10 | head -c 150; echo
echo "=== FR-4.1 订单查询 ==="
curl -s "$B/api/v1/appointments/$ORDER" -H "authorization: Bearer $AT" --max-time 10 | head -c 250; echo