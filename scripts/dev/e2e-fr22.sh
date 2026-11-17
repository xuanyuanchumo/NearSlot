#!/usr/bin/env bash
B=http://localhost:48081
LOGIN=$(curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass123"}' --max-time 10)
AT=$(echo "$LOGIN" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['accessToken'] if d.get('code')==200 and d.get('data') else '')" 2>/dev/null)
echo "=== FR-2.2 发布时段（未来 10 分钟，应排期提醒）==="
PUB=$(curl -s -X POST $B/api/v1/slots -H "Content-Type: application/json" -H "authorization: Bearer $AT" -d '{"shopId":1,"technicianId":2001,"startsAt":"2026-11-17T14:30:00","endsAt":"2026-11-17T15:30:00","capacity":3,"minLevel":0}' --max-time 10)
echo "$PUB" | head -c 200; echo
SLOT=$(echo "$PUB" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data'] if d.get('code')==200 and d.get('data') else '')" 2>/dev/null)
echo "NEW_SLOT=$SLOT"
echo "=== 验证余量已预热 ==="
docker exec ns-verify-redis redis-cli -a verify123 GET "nearslot:slot:stock:$SLOT" 2>/dev/null | tr -d "\r"
echo "=== FR-3.4 发布即将开始的时段（1 分钟内，应立即提醒）==="
SOON=$(date -d "+50 seconds" +"%Y-%m-%dT%H:%M:%S" 2>/dev/null || python -c "import datetime;print((datetime.datetime.now()+datetime.timedelta(seconds=50)).strftime('%Y-%m-%dT%H:%M:%S'))")
SOONEND=$(date -d "+50 minutes" +"%Y-%m-%dT%H:%M:%S" 2>/dev/null || python -c "import datetime;print((datetime.datetime.now()+datetime.timedelta(minutes=50)).strftime('%Y-%m-%dT%H:%M:%S'))")
curl -s -X POST $B/api/v1/slots -H "Content-Type: application/json" -H "authorization: Bearer $AT" -d "{\"shopId\":1,\"technicianId\":2001,\"startsAt\":\"$SOON\",\"endsAt\":\"$SOONEND\",\"capacity\":1,\"minLevel\":0}" --max-time 10 | head -c 160; echo
sleep 3