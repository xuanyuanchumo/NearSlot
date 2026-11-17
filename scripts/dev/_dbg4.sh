cd /d/Projects/TraeProjects/Studying/NearSlot
P=$(grep -E "^REDIS_PASSWORD=" .env | cut -d= -f2-)
CLI=/d/Redis/redis-cli.exe
echo "--- all nearslot keys:"
"$CLI" -p 46379 -a "$P" KEYS "nearslot:*" 2>/dev/null
echo "--- slot:user:3001 members:"
"$CLI" -p 46379 -a "$P" SMEMBERS "nearslot:slot:user:3001" 2>/dev/null
echo "--- type check:"
"$CLI" -p 46379 -a "$P" TYPE "nearslot:slot:user:3001" 2>/dev/null