B=http://localhost:8081/api/v1
RD=$(grep -E "^REDIS_PASSWORD=" .env | cut -d= -f2-)
RC() { cd /d/Redis && ./redis-cli.exe -p 46379 -a "$RD" "$@" 2>/dev/null; }
AT=$(curl -s -X POST $B/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass12345"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
H="authorization: Bearer $AT"
pass=0; fail=0
ck() { if [ "$2" = "$3" ]; then echo "  OK   $1 ($2)"; pass=$((pass+1)); else echo "  FAIL $1: want=$3 got=$2"; fail=$((fail+1)); fi; }
echo "== 1) MP 分页 =="
R=$(curl -s "$B/shops?page=1&size=5")
ck "items" "$(echo "$R" | python -c "import sys,json;d=json.load(sys.stdin)['data'];print('yes' if 'items' in d else 'no')")" "yes"
ck "total" "$(echo "$R" | python -c "import sys,json;d=json.load(sys.stdin)['data'];print('yes' if 'total' in d else 'no')")" "yes"
ck "pages" "$(echo "$R" | python -c "import sys,json;d=json.load(sys.stdin)['data'];print('yes' if 'pages' in d else 'no')")" "yes"
echo "== 2) 管理端分页 =="
ck "技师分页" "$(curl -s "$B/admin/technicians?shopId=1&page=1&size=10" -H "$H" | python -c "import sys,json;print(json.load(sys.stdin)['code'])")" "200"
ck "服务项分页" "$(curl -s "$B/admin/service-items?shopId=1&page=1&size=10" -H "$H" | python -c "import sys,json;print(json.load(sys.stdin)['code'])")" "200"
echo "== 3) 技能绑定 =="
mysql -h127.0.0.1 -P3306 -uroot -N -e "DELETE FROM nearslot.technician_skill WHERE technician_id=2001; INSERT IGNORE INTO nearslot.service_item (id,shop_id,name,duration_min,price_cent,item_type,lead_time_min) VALUES (4001,1,'肩颈推拿',60,19800,'SERVICE',0);" 2>/dev/null
ck "绑定" "$(curl -s -X POST "$B/admin/technicians/2001/skills" -H "$H" -H "Content-Type: application/json" -d '{"serviceItemId":4001,"proficiency":5}' | python -c "import sys,json;print(json.load(sys.stdin)['code'])")" "200"
ck "幂等重复绑定" "$(curl -s -X POST "$B/admin/technicians/2001/skills" -H "$H" -H "Content-Type: application/json" -d '{"serviceItemId":4001,"proficiency":4}' | python -c "import sys,json;print(json.load(sys.stdin)['code'])")" "200"
ck "列表含名称" "$(curl -s "$B/admin/technicians/2001/skills" -H "$H" | python -c "import sys,json;d=json.load(sys.stdin)['data'];print(d[0]['serviceItemName'] if d else 'empty')")" "肩颈推拿"
ck "熟练度=4" "$(curl -s "$B/admin/technicians/2001/skills" -H "$H" | python -c "import sys,json;print(json.load(sys.stdin)['data'][0]['proficiency'])")" "4"
echo "== 4) 核销全链路 =="
mysql -h127.0.0.1 -P3306 -uroot -N -e "DELETE FROM nearslot.appointment_voucher; DELETE FROM nearslot.appointment_order WHERE slot_id=3001; UPDATE nearslot.time_slot SET status=1,capacity=2 WHERE id=3001;" 2>/dev/null
RC DEL nearslot:slot:user:3001 >/dev/null; RC SET nearslot:slot:stock:3001 2 >/dev/null; RC DEL "nearslot:idem:1001:3001" >/dev/null
OID=$(curl -s -X POST $B/appointments -H "Content-Type: application/json" -H "$H" -d '{"slotId":3001}' | python -c "import sys,json;d=json.load(sys.stdin);print(d['data'] if d.get('code')==200 else '')" 2>/dev/null)
ck "抢约成功" "$([ -n "$OID" ] && echo yes || echo no)" "yes"
ck "支付前无码(404)" "$(curl -s -o /dev/null -w "%{http_code}" "$B/vouchers/orders/$OID" -H "$H")" "404"
ck "支付回调" "$(curl -s -X POST $B/payments/callback -H "Content-Type: application/json" -d '{"orderId":'$OID',"payNo":"PAY-1"}' | python -c "import sys,json;print(json.load(sys.stdin)['data'])")" "CONFIRMED"
CODE=$(curl -s "$B/vouchers/orders/$OID" -H "$H" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['code'] if d.get('code')==200 else '')" 2>/dev/null)
ck "签发12位码" "$(echo -n "$CODE" | wc -c | tr -d " ")" "12"
ck "CONSUMER核销403" "$(curl -s -o /dev/null -w "%{http_code}" -X POST $B/vouchers/redeem -H "Content-Type: application/json" -H "$H" -d '{"code":"'$CODE'"}')" "403"
echo "  (PLATFORM 核销)"; ck "PLATFORM核销" "$(curl -s -X POST $B/vouchers/redeem -H "Content-Type: application/json" -H "$H" -d '{"code":"'$CODE'"}' | python -c "import sys,json;print(json.load(sys.stdin)['code'])")" "200"
ck "订单转DONE(4)" "$(curl -s "$B/appointments/$OID" -H "$H" | python -c "import sys,json;print(json.load(sys.stdin)['data']['status'])")" "4"
ck "重复核销被拒" "$(curl -s -X POST $B/vouchers/redeem -H "Content-Type: application/json" -H "$H" -d '{"code":"'$CODE'"}' | python -c "import sys,json;print(json.load(sys.stdin)['code'])")" "40001"
echo "=========="; echo "PASS=$pass FAIL=$fail"