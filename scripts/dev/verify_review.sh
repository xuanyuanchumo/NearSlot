cd /d/Projects/TraeProjects/Studying/NearSlot
B=http://localhost:8081/api/v1
pass=0; fail=0
ck() { if [ "$2" = "$3" ]; then echo "  OK   $1 ($2)"; pass=$((pass+1)); else echo "  FAIL $1: want=$3 got=$2"; fail=$((fail+1)); fi; }
MQ() { docker exec nearslot-mysql sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N -e \"$1\"" 2>/dev/null | tail -1; }
AT=$(curl -s -X POST $B/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass12345"}' | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['accessToken'] if d.get('code')==200 else '')" 2>/dev/null)
AT_M=$(curl -s -X POST $B/auth/login -H "Content-Type: application/json" -d '{"phone":"13900139000","password":"pass12345"}' | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['accessToken'] if d.get('code')==200 else '')" 2>/dev/null)
H="authorization: Bearer $AT"; HM="authorization: Bearer $AT_M"

echo "== 清理上轮残留（否则技师时间轴重叠会被正确拒绝）=="
# 注意：MSYS 的 /tmp 在多次 bash 调用间不可靠（曾导致 curl -d @file 读不到），
# 一律用工作区内临时目录
W=./_e2e_tmp; mkdir -p "$W"
PWD_REDIS=$(grep -E '^REDIS_PASSWORD=' .env | cut -d= -f2-)
# 清掉本技师的历史订单与时段，并清空相关 Redis 键：
# 不清会导致"技师时间轴不重叠"规则（正确行为）拒绝本轮抢约，E2E 变成非幂等。
# 必须连**技师时间轴**一起清：Lua 用区间相交判定占用，残留时间轴会让新时段恒判"已占用"。
docker exec nearslot-redis sh -c "for pat in 'nearslot:slot:*' 'nearslot:staff:*' 'nearslot:tech:*'; do redis-cli -a '$PWD_REDIS' --scan --pattern \"\$pat\" | xargs -r redis-cli -a '$PWD_REDIS' DEL; done" >/dev/null 2>&1
docker exec nearslot-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" nearslot -e "DELETE FROM appointment_review; DELETE FROM appointment_voucher; DELETE FROM appointment_order; DELETE FROM time_slot; UPDATE technician SET rating_avg=500, rating_count=0 WHERE id=1;"' >/dev/null 2>&1
python -c "import json,datetime;n=datetime.datetime.now();print(json.dumps({'shopId':1,'serviceStaffId':1,'serviceItemId':1,'startsAt':(n+datetime.timedelta(hours=2)).strftime('%Y-%m-%dT%H:%M:%S'),'endsAt':(n+datetime.timedelta(hours=3)).strftime('%Y-%m-%dT%H:%M:%S'),'capacity':2,'minLevel':0}))" > "$W/slot.json"
SID=$(curl -s -X POST $B/slots -H "$HM" -H "Content-Type: application/json" -d @"$W/slot.json" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data'] if d.get('code')==200 else '')" 2>/dev/null)
ck "发布时段" "$([ -n "$SID" ] && echo yes || echo no)" "yes"

echo "== 闭环：抢约 -> 支付 -> 核销 -> 评价 =="
OID=$(curl -s -X POST $B/appointments -H "Content-Type: application/json" -H "$H" -d "{\"slotId\":$SID}" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data'] if d.get('code')==200 else '')" 2>/dev/null)
ck "抢约成功" "$([ -n "$OID" ] && echo yes || echo no)" "yes"
ck "未完成订单不能评价(403)" "$(curl -s -X POST $B/reviews -H "$H" -H "Content-Type: application/json" -d "{\"orderId\":$OID,\"rating\":5}" | python -c "import sys,json;print(json.load(sys.stdin)['code'])")" "40301"
curl -s -X POST $B/payments/callback -H "Content-Type: application/json" -d "{\"orderId\":$OID,\"payNo\":\"P1\"}" >/dev/null
CODE=$(curl -s "$B/vouchers/orders/$OID" -H "$H" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['code'] if d.get('code')==200 else '')" 2>/dev/null)
curl -s -X POST $B/vouchers/redeem -H "$HM" -H "Content-Type: application/json" -d "{\"code\":\"$CODE\"}" >/dev/null
ck "订单已完成(4)" "$(curl -s "$B/appointments/$OID" -H "$H" | python -c "import sys,json;print(json.load(sys.stdin)['data']['status'])")" "4"

echo "== FR-12 评价 =="
ck "评分越界被拒" "$(curl -s -X POST $B/reviews -H "$H" -H "Content-Type: application/json" -d "{\"orderId\":$OID,\"rating\":6}" | python -c "import sys,json;print(json.load(sys.stdin)['code'])")" "40001"
RID=$(curl -s -X POST $B/reviews -H "$H" -H "Content-Type: application/json" -d "{\"orderId\":$OID,\"rating\":5,\"content\":\"手法专业\",\"images\":\"a.jpg,b.jpg\"}" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data'] if d.get('code')==200 else '')" 2>/dev/null)
ck "已完成订单可评价" "$([ -n "$RID" ] && echo yes || echo no)" "yes"
ck "重复评价被拒(40001)" "$(curl -s -X POST $B/reviews -H "$H" -H "Content-Type: application/json" -d "{\"orderId\":$OID,\"rating\":4}" | python -c "import sys,json;print(json.load(sys.stdin)['code'])")" "40001"
ck "技师评分回写" "$(MQ "select concat(rating_avg,'/',rating_count) from nearslot.technician where id=1")" "500/1"
ck "评分概况" "$(curl -s "$B/reviews/technicians/1/stat" | python -c "import sys,json;d=json.load(sys.stdin)['data'];print(str(d['count'])+'/'+str(d['avg']))")" "1/5.0"
ck "店铺评价列表(公开)" "$(curl -s "$B/reviews/shops/1" | python -c "import sys,json;print(json.load(sys.stdin)['data']['total'])")" "1"
ck "我的评价" "$(curl -s "$B/reviews/mine" -H "$H" | python -c "import sys,json;print(json.load(sys.stdin)['data']['total'])")" "1"
ck "非本店商家回复被拒(403)" "$(curl -s -o /dev/null -w "%{http_code}" -X POST "$B/reviews/$RID/reply" -H "$H" -H "Content-Type: application/json" -d '{"reply":"x"}')" "403"
ck "本店商家可回复" "$(curl -s -X POST "$B/reviews/$RID/reply" -H "$HM" -H "Content-Type: application/json" -d '{"reply":"感谢反馈"}' | python -c "import sys,json;print(json.load(sys.stdin)['code'])")" "200"
ck "回复已展示" "$(curl -s "$B/reviews/shops/1" | python -c "import sys,json;d=json.load(sys.stdin)['data']['items'][0];print('yes' if d['merchantReply'] else 'no')")" "yes"
ck "撤回评价" "$(curl -s -X DELETE "$B/reviews/$RID" -H "$H" | python -c "import sys,json;print(json.load(sys.stdin)['code'])")" "200"
# 评审 MAJOR-1 语义变更：撤到 0 条 -> 0 表示"未评级"，不再回落 500（防差评技师被抬成满分）
ck "评分已清零(0=未评级)" "$(MQ "select concat(rating_avg,'/',rating_count) from nearslot.technician where id=1")" "0/0"
echo "=========="; echo "PASS=$pass FAIL=$fail"
# 评审 E-002：此前只 echo FAIL 不 exit，CI/自动流程里失败也算成功（假绿门禁）
if [ "$fail" -gt 0 ]; then exit 1; fi
exit 0