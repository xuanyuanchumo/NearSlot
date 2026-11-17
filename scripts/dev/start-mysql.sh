#!/usr/bin/env bash
cd "D:/Projects/TraeProjects/Studying/NearSlot" || exit 9
docker rm -f nearslot-mysql >/dev/null 2>&1
docker run -d --name nearslot-mysql -p 43306:3306 \
  -e MYSQL_DATABASE=nearslot -e MYSQL_USER=nearslot \
  -e MYSQL_PASSWORD=devpass123 -e MYSQL_ROOT_PASSWORD=rootdev123 \
  -v "$(pwd)/sql:/docker-entrypoint-initdb.d:ro" mysql:8.0 >/dev/null 2>&1
echo "waiting for mysql..."
for i in $(seq 1 30); do
  if docker exec nearslot-mysql mysqladmin ping -h localhost -uroot -prootdev123 2>/dev/null | grep -q alive; then
    echo "MySQL ready after $((i*10))s"
    break
  fi
  sleep 10
done
docker ps --filter "name=nearslot-mysql" --format "{{.Names}} {{.Status}}"