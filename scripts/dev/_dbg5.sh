cd /d/Projects/TraeProjects/Studying/NearSlot
P=$(grep -E "^REDIS_PASSWORD=" .env | cut -d= -f2-)
CLI=/d/Redis/redis-cli.exe
B=http://localhost:8081/api/v1
AT=$(curl -s -X POST $B/auth/login -H "Content-Type: application/json" -d '{"phone":"13800138000","password":"pass12345"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
echo "1) DEL user set:"; "$CLI" -p 46379 -a "$P" DEL nearslot:slot:user:3001 2>/dev/null
echo "2) SMEMBERS now: [$( "$CLI" -p 46379 -a "$P" SMEMBERS nearslot:slot:user:3001 2>/dev/null )]"
echo "3) BOOK:"; curl -s -X POST $B/appointments -H "Content-Type: application/json" -H "authorization: Bearer $AT" -d '{"slotId":3001}'; echo
echo "4) SMEMBERS after: [$( "$CLI" -p 46379 -a "$P" SMEMBERS nearslot:slot:user:3001 2>/dev/null )]"
echo "5) EXISTS key: $( "$CLI" -p 46379 -a "$P" EXISTS nearslot:slot:user:3001 2>/dev/null )"