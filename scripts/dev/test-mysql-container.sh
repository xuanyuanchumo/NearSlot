#!/usr/bin/env bash
cd "D:/Projects/TraeProjects/Studying/NearSlot" || exit 9
echo "=== 用已拉取的 mysql:8.0 起容器（端口 43306，避开本机 3306）==="
docker rm -f nearslot-mysql-test >/dev/null 2>&1
docker run -d --name nearslot-mysql-test -p 43306:3306 \
  -e MYSQL_DATABASE=nearslot -e MYSQL_USER=nearslot \
  -e MYSQL_PASSWORD=devpass123 -e MYSQL_ROOT_PASSWORD=rootdev123 \
  mysql:8.0 >/dev/null 2>&1 && echo "容器已启动" || echo "启动失败"
echo "等待 MySQL 就绪..."
for i in $(seq 1 24); do
  if docker exec nearslot-mysql-test mysqladmin ping -h localhost -uroot -prootdev123 2>/dev/null | grep -q alive; then
    echo "就绪（$((i*5))s）"
    break
  fi
  sleep 5
done
docker ps --filter "name=nearslot-mysql-test" --format "{{.Names}} | {{.Status}}"