#!/usr/bin/env bash
cd "D:/Projects/TraeProjects/Studying/NearSlot" || exit 9
MYSQL="D:/MySQL/MySQL Server 8.0/bin/mysql"
echo "=== 建库 ==="
"$MYSQL" -uroot -h127.0.0.1 -P3306 < sql/1_create_database.sql 2>&1 | head -5
echo "=== 建表 ==="
"$MYSQL" -uroot -h127.0.0.1 -P3306 < sql/2_business.sql 2>&1 | head -5
echo "=== 迁移脚本 ==="
for f in sql/3_*.sql sql/4_*.sql; do
  [ -f "$f" ] && echo "  applying $f" && "$MYSQL" -uroot -h127.0.0.1 -P3306 nearslot < "$f" 2>&1 | head -3
done
echo "=== 表清单 ==="
"$MYSQL" -uroot -h127.0.0.1 -P3306 -N -e "SELECT table_name FROM information_schema.tables WHERE table_schema=\"nearslot\" ORDER BY table_name;" 2>&1 | head -20