B=http://localhost:8081/api/v1
RD=$(grep -E "^REDIS_PASSWORD=" .env | cut -d= -f2-)
RC() { cd /d/Redis && ./redis-cli.exe -p 46379 -a "$RD" "$@" 2>/dev/null; }
AT=$(curl -s -X POST $B/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass12345"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
echo "--- booking raw:"; curl -s -X POST $B/appointments -H "Content-Type: application/json" -H "authorization: Bearer $AT" -d '{"slotId":3001}'; echo
echo "--- redis stock:"; RC GET nearslot:slot:stock:3001
echo "--- redis user set:"; RC SMEMBERS nearslot:slot:user:3001
echo "--- redis timeline:"; RC ZRANGE "nearslot:staff:timeline:2001" 0 -1 WITHSCORES
echo "--- DB orders:"; mysql -h127.0.0.1 -P3306 -uroot -N -e "select id,user_id,slot_id,status from nearslot.appointment_order where slot_id=3001" 2>/dev/null