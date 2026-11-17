#!/usr/bin/env bash
B=http://localhost:48081
LOGIN=$(curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass123"}' --max-time 10)
AT=$(echo "$LOGIN" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['accessToken'] if d.get('code')==200 and d.get('data') else '')" 2>/dev/null)
echo "=== 发布时段（MQ 不可用，应走降级）==="
curl -s -X POST $B/api/v1/slots -H "Content-Type: application/json" -H "authorization: Bearer $AT" -d '{"shopId":1,"technicianId":2001,"startsAt":"2026-11-18T10:00:00","endsAt":"2026-11-18T11:00:00","capacity":2,"minLevel":0}' --max-time 10 | head -c 150; echo
sleep 2