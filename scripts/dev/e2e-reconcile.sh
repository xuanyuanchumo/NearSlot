#!/usr/bin/env bash
B=http://localhost:48081
echo "=== 登录 ==="
LOGIN=$(curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass123"}' --max-time 10)
TOKEN=$(echo "$LOGIN" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['accessToken'] if d.get('code')==200 and d.get('data') else '')" 2>/dev/null)
echo "TOKEN_LEN=${#TOKEN}"
echo "=== 抢约（造一笔订单用于对账）==="
BOOK=$(curl -s -X POST $B/api/v1/appointments -H "Content-Type: application/json" -H "authorization: Bearer $TOKEN" -d '{"slotId":3001}' --max-time 10)
echo "$BOOK" | head -c 200; echo
echo "=== 等待对账任务执行（>5min 太长，改为直接触发一次）==="
sleep 2
echo "已完成抢约，等待下次对账周期（5min）或在日志中观察"