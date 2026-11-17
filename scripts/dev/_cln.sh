cd /d/Projects/TraeProjects/Studying/NearSlot
P=$(grep -E "^REDIS_PASSWORD=" .env | cut -d= -f2-)
echo "pwd_len=${#P}"
CLI=/d/Redis/redis-cli.exe
echo "--- del test:"
"$CLI" -p 46379 -a "$P" DEL nearslot:slot:user:3001 2>&1 | tail -2
echo "--- smembers after:"
"$CLI" -p 46379 -a "$P" SMEMBERS nearslot:slot:user:3001 2>&1 | tail -2