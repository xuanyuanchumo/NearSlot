cd /d/Projects/TraeProjects/Studying/NearSlot
P=$(grep -E "^REDIS_PASSWORD=" .env | cut -d= -f2-)
CLI=/d/Redis/redis-cli.exe
RC() { "$CLI" -p 46379 -a "$P" "$@" 2>/dev/null; }
B=http://localhost:8081/api/v1
AT=$(curl -s -X POST $B/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass12345"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
H="authorization: Bearer $AT"
pass=0; fail=0
ck() { if [ "$2" = "$3" ]; then echo "  OK   $1 ($2)"; pass=$((pass+1)); else echo "  FAIL $1: want=$3 got=$2"; fail=$((fail+1)); fi; }
mysql -h127.0.0.1 -P3306 -uroot -N -e "DELETE FROM nearslot.appointment_voucher; DELETE FROM nearslot.appointment_order WHERE slot_id=3001;" 2>/dev/null
RC DEL nearslot:slot:user:3001 >/dev/null
RC DEL nearslot:slot:trace:3001 >/dev/null
RC SET nearslot:slot:stock:3001 2 >/dev/null
echo "  pre-check user_set=[$(RC SMEMBERS nearslot:slot:user:3001)] stock=$(RC GET nearslot:slot:stock:3001)"
OID=$(curl -s -X POST $B/appointments -H "Content-Type: application/json" -H "$H" -d '{"slotId":3001}' | python -c "import sys,json;d=json.load(sys.stdin);print(d['data'] if d.get('code')==200 else '')" 2>/dev/null)
ck "抢约成功" "$([ -n "$OID" ] && echo yes || echo no)" "yes"
ck "支付前无码404" "$(curl -s -o /dev/null -w "%{http_code}" "$B/vouchers/orders/$OID" -H "$H")" "404"
ck "支付回调" "$(curl -s -X POST $B/payments/callback -H "Content-Type: application/json" -d '{"orderId":'$OID',"payNo":"PAY-1"}' | python -c "import sys,json;print(json.load(sys.stdin)['data'])")" "CONFIRMED"
CODE=$(curl -s "$B/vouchers/orders/$OID" -H "$H" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['code'] if d.get('code')==200 else '')" 2>/dev/null)
echo "  CODE=$CODE"
ck "签发12位" "$(echo -n "$CODE" | wc -c | tr -d " ")" "12"
ck "CONSUMER核销403" "$(curl -s -o /dev/null -w "%{http_code}" -X POST $B/vouchers/redeem -H "Content-Type: application/json" -H "$H" -d '{"code":"'$CODE'"}')" "403"
ck "PLATFORM核销200" "$(curl -s -X POST $B/vouchers/redeem -H "Content-Type: application/json" -H "$H" -d '{"code":"'$CODE'"}' | python -c "import sys,json;print(json.load(sys.stdin)['code'])")" "200"
ck "订单转DONE(4)" "$(curl -s "$B/appointments/$OID" -H "$H" | python -c "import sys,json;print(json.load(sys.stdin)['data']['status'])")" "4"
ck "重复核销40001" "$(curl -s -X POST $B/vouchers/redeem -H "Content-Type: application/json" -H "$H" -d '{"code":"'$CODE'"}' | python -c "import sys,json;print(json.load(sys.stdin)['code'])")" "40001"
echo "=========="; echo "PASS=$pass FAIL=$fail"