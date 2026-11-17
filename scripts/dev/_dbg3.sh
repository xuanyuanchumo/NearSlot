cd /d/Projects/TraeProjects/Studying/NearSlot
B=http://localhost:8081/api/v1
AT=$(curl -s -X POST $B/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass12345"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
echo "--- raw book:"
curl -s -X POST $B/appointments -H "Content-Type: application/json" -H "authorization: Bearer $AT" -d '{"slotId":3001}'
echo ""
echo "--- slot business fields:"
mysql -h127.0.0.1 -P3306 -uroot -N -e "select id,shop_id,service_staff_id,status,capacity,starts_at,ends_at,min_level from nearslot.time_slot where id=3001" 2>/dev/null
echo "--- now:"; date