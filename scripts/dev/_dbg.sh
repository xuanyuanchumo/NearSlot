B=http://localhost:8081/api/v1
AT=$(curl -s -X POST $B/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass12345"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
echo "--- 技师分页原始响应"; curl -s "$B/admin/technicians?shopId=1&page=1&size=10" -H "authorization: Bearer $AT" | head -c 400; echo
echo "--- 技能绑定原始响应"; curl -s -X POST "$B/admin/technicians/2001/skills" -H "authorization: Bearer $AT" -H "Content-Type: application/json" -d '{"serviceItemId":4001,"proficiency":5}' | head -c 400; echo
echo "--- 抢约原始响应"; curl -s -X POST $B/appointments -H "Content-Type: application/json" -H "authorization: Bearer $AT" -d '{"slotId":3001}' | head -c 400; echo
echo "--- slots 现状"; curl -s "$B/slots?shopId=1" | head -c 400; echo
echo "--- DB 技师"; mysql -h127.0.0.1 -P3306 -uroot -N -e "select id,shop_id,name from nearslot.technician limit 5" 2>/dev/null