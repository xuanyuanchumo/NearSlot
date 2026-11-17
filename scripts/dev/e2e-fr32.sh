#!/usr/bin/env bash
B=http://localhost:48081
LOGIN=$(curl -s -X POST $B/api/v1/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass123"}' --max-time 10)
AT=$(echo "$LOGIN" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['accessToken'] if d.get('code')==200 and d.get('data') else '')" 2>/dev/null)
echo "=== 1) 发布需 Lv8 的时段 ==="
HIGH=$(curl -s -X POST $B/api/v1/slots -H "Content-Type: application/json" -H "authorization: Bearer $AT" -d '{"shopId":1,"technicianId":2001,"startsAt":"2026-11-19T10:00:00","endsAt":"2026-11-19T11:00:00","capacity":1,"minLevel":8}' --max-time 10)
echo "$HIGH" | head -c 130; echo
HSLOT=$(echo "$HIGH" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data'] if d.get('code')==200 and d.get('data') else '')" 2>/dev/null)
echo "=== 2) Lv5 用户候补 Lv8 时段（应 50104 拒绝）==="
curl -s -X POST $B/api/v1/appointments/$HSLOT/waitlist -H "authorization: Bearer $AT" --max-time 10 | head -c 180; echo
echo "=== 3) 无门槛时段：抢约 -> 候补 -> 取消（应触发提升）==="
LOW=$(curl -s -X POST $B/api/v1/slots -H "Content-Type: application/json" -H "authorization: Bearer $AT" -d '{"shopId":1,"technicianId":2001,"startsAt":"2026-11-20T10:00:00","endsAt":"2026-11-20T11:00:00","capacity":1,"minLevel":0}' --max-time 10)
LSLOT=$(echo "$LOW" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data'] if d.get('code')==200 and d.get('data') else '')" 2>/dev/null)
echo "SLOT=$LSLOT"
curl -s -X POST $B/api/v1/appointments/$LSLOT/waitlist -H "authorization: Bearer $AT" --max-time 10 | head -c 120; echo
BOOK=$(curl -s -X POST $B/api/v1/appointments -H "Content-Type: application/json" -H "authorization: Bearer $AT" -d "{\"slotId\":$LSLOT}" --max-time 10)
echo "抢约: $(echo $BOOK | head -c 100)"
ORDER=$(echo "$BOOK" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data'] if d.get('code')==200 and d.get('data') else '')" 2>/dev/null)
echo "=== 4) 取消（应异步提升候补）==="
curl -s -X POST $B/api/v1/appointments/$ORDER/cancel -H "authorization: Bearer $AT" --max-time 10 | head -c 120; echo
sleep 3