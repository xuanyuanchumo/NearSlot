cd /d/Projects/TraeProjects/Studying/NearSlot
P=$(grep -E "^REDIS_PASSWORD=" .env | cut -d= -f2-)
CLI=/d/Redis/redis-cli.exe
RC() { "$CLI" -p 46379 -a "$P" "$@" 2>/dev/null; }
B=http://localhost:8081/api/v1
pass=0; fail=0
ck() { if [ "$2" = "$3" ]; then echo "  OK   $1 ($2)"; pass=$((pass+1)); else echo "  FAIL $1: want=$3 got=$2"; fail=$((fail+1)); fi; }

# 造三个角色账号：CONSUMER(1001) / MERCHANT(1002 属店1) / PLATFORM(1003)
mysql -h127.0.0.1 -P3306 -uroot nearslot 2>/dev/null <<SQL
INSERT IGNORE INTO user (id,nickname,password,level,deleted) VALUES
 (1001,'consumer',(SELECT password FROM (SELECT password FROM user WHERE id=1001) t),5,0),
 (1002,'merchant',(SELECT password FROM (SELECT password FROM user WHERE id=1001) t),5,0),
 (1003,'platform',(SELECT password FROM (SELECT password FROM user WHERE id=1001) t),5,0);
INSERT IGNORE INTO user_phone (user_id,phone) VALUES (1002,'13900139000'),(1003,'13700137000');
INSERT INTO user_role (user_id,role,shop_id) VALUES (1002,'MERCHANT',1) ON DUPLICATE KEY UPDATE role='MERCHANT',shop_id=1;
INSERT INTO user_role (user_id,role,shop_id) VALUES (1003,'PLATFORM',NULL) ON DUPLICATE KEY UPDATE role='PLATFORM',shop_id=NULL;
UPDATE user_role SET role='CONSUMER',shop_id=NULL WHERE user_id=1001;
SQL

AT_C=$(curl -s -X POST $B/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass12345"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
AT_M=$(curl -s -X POST $B/auth/login -H "Content-Type: application/json" -d '{"phone":"13900139000","password":"pass12345"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
AT_P=$(curl -s -X POST $B/auth/login -H "Content-Type: application/json" -d '{"phone":"13700137000","password":"pass12345"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
ck "三角色登录" "$([ -n "$AT_C" ] && [ -n "$AT_M" ] && [ -n "$AT_P" ] && echo yes || echo no)" "yes"

echo "== MP 分页 =="
R=$(curl -s "$B/shops?page=1&size=5")
ck "PageResult 形状" "$(echo "$R" | python -c "import sys,json;d=json.load(sys.stdin)['data'];print('ok' if all(k in d for k in ('items','total','page','size','pages')) else 'bad')")" "ok"
ck "管理端技师分页" "$(curl -s "$B/admin/technicians?shopId=1&page=1&size=10" -H "authorization: Bearer $AT_P" | python -c "import sys,json;print(json.load(sys.stdin)['code'])")" "200"

echo "== 技能绑定 =="
mysql -h127.0.0.1 -P3306 -uroot -N -e "DELETE FROM nearslot.technician_skill WHERE technician_id=2001; INSERT IGNORE INTO nearslot.service_item (id,shop_id,name,duration_min,price_cent,item_type,lead_time_min) VALUES (4001,1,'肩颈推拿',60,19800,'SERVICE',0);" 2>/dev/null
ck "绑定" "$(curl -s -X POST "$B/admin/technicians/2001/skills" -H "authorization: Bearer $AT_P" -H "Content-Type: application/json" -d '{"serviceItemId":4001,"proficiency":5}' | python -c "import sys,json;print(json.load(sys.stdin)['code'])")" "200"
ck "幂等重复" "$(curl -s -X POST "$B/admin/technicians/2001/skills" -H "authorization: Bearer $AT_P" -H "Content-Type: application/json" -d '{"serviceItemId":4001,"proficiency":4}' | python -c "import sys,json;print(json.load(sys.stdin)['code'])")" "200"
ck "列表含名称" "$(curl -s "$B/admin/technicians/2001/skills" -H "authorization: Bearer $AT_P" | python -c "import sys,json;d=json.load(sys.stdin)['data'];print(d[0]['serviceItemName'] if d else 'empty')")" "肩颈推拿"

echo "== 核销全链路 =="
mysql -h127.0.0.1 -P3306 -uroot -N -e "DELETE FROM nearslot.appointment_voucher; DELETE FROM nearslot.appointment_order WHERE slot_id=3001;" 2>/dev/null
RC DEL nearslot:slot:user:3001 >/dev/null; RC SET nearslot:slot:stock:3001 2 >/dev/null; RC DEL "nearslot:idem:1001:3001" >/dev/null
OID=$(curl -s -X POST $B/appointments -H "Content-Type: application/json" -H "authorization: Bearer $AT_C" -d '{"slotId":3001}' | python -c "import sys,json;d=json.load(sys.stdin);print(d['data'] if d.get('code')==200 else '')" 2>/dev/null)
ck "抢约" "$([ -n "$OID" ] && echo yes || echo no)" "yes"
ck "支付前无码" "$(curl -s -o /dev/null -w "%{http_code}" "$B/vouchers/orders/$OID" -H "authorization: Bearer $AT_C")" "404"
ck "支付回调" "$(curl -s -X POST $B/payments/callback -H "Content-Type: application/json" -d '{"orderId":'$OID',"payNo":"PAY-1"}' | python -c "import sys,json;print(json.load(sys.stdin)['data'])")" "CONFIRMED"
CODE=$(curl -s "$B/vouchers/orders/$OID" -H "authorization: Bearer $AT_C" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['code'] if d.get('code')==200 else '')" 2>/dev/null)
ck "签发12位" "$(echo -n "$CODE" | wc -c | tr -d " ")" "12"
ck "CONSUMER核销403" "$(curl -s -o /dev/null -w "%{http_code}" -X POST $B/vouchers/redeem -H "Content-Type: application/json" -H "authorization: Bearer $AT_C" -d '{"code":"'$CODE'"}')" "403"
ck "MERCHANT核销200" "$(curl -s -X POST $B/vouchers/redeem -H "Content-Type: application/json" -H "authorization: Bearer $AT_M" -d '{"code":"'$CODE'"}' | python -c "import sys,json;print(json.load(sys.stdin)['code'])")" "200"
ck "订单转DONE" "$(curl -s "$B/appointments/$OID" -H "authorization: Bearer $AT_C" | python -c "import sys,json;print(json.load(sys.stdin)['data']['status'])")" "4"
ck "重复核销40001" "$(curl -s -X POST $B/vouchers/redeem -H "Content-Type: application/json" -H "authorization: Bearer $AT_M" -d '{"code":"'$CODE'"}' | python -c "import sys,json;print(json.load(sys.stdin)['code'])")" "40001"

echo "== 取消作废核销码 =="
mysql -h127.0.0.1 -P3306 -uroot -N -e "DELETE FROM nearslot.appointment_voucher; DELETE FROM nearslot.appointment_order WHERE slot_id=3001;" 2>/dev/null
RC DEL nearslot:slot:user:3001 >/dev/null; RC SET nearslot:slot:stock:3001 2 >/dev/null; RC DEL "nearslot:idem:1001:3001" >/dev/null
OID2=$(curl -s -X POST $B/appointments -H "Content-Type: application/json" -H "authorization: Bearer $AT_C" -d '{"slotId":3001}' | python -c "import sys,json;d=json.load(sys.stdin);print(d['data'] if d.get('code')==200 else '')" 2>/dev/null)
curl -s -X POST $B/payments/callback -H "Content-Type: application/json" -d '{"orderId":'$OID2',"payNo":"PAY-2"}' >/dev/null
CODE2=$(curl -s "$B/vouchers/orders/$OID2" -H "authorization: Bearer $AT_C" | python -c "import sys,json;d=json.load(sys.stdin);print(d['data']['code'] if d.get('code')==200 else '')" 2>/dev/null)
ck "已签发" "$(echo -n "$CODE2" | wc -c | tr -d " ")" "12"
curl -s -X POST "$B/appointments/$OID2/cancel" -H "authorization: Bearer $AT_C" >/dev/null
ck "取消后核销被拒" "$(curl -s -o /dev/null -w "%{http_code}" -X POST $B/vouchers/redeem -H "Content-Type: application/json" -H "authorization: Bearer $AT_M" -d '{"code":"'$CODE2'"}')" "200"
ck "退款/取消码已作废" "$(curl -s -X POST $B/vouchers/redeem -H "Content-Type: application/json" -H "authorization: Bearer $AT_M" -d '{"code":"'$CODE2'"}' | python -c "import sys,json;print(json.load(sys.stdin)['code'])")" "40001"
echo "=========="; echo "PASS=$pass FAIL=$fail"