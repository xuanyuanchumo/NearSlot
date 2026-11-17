#!/usr/bin/env bash
cd "D:/Projects/TraeProjects/Studying/NearSlot" || exit 9
echo "=== 容器内建库建表 ==="
docker exec -i nearslot-mysql-test mysql -uroot -prootdev123 < sql/2_business.sql 2>&1 | head -3
echo "=== 表数量 ==="
docker exec nearslot-mysql-test mysql -uroot -prootdev123 -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='nearslot';" 2>&1 | head -2
echo "=== 种子数据 ==="
docker exec -i nearslot-mysql-test mysql -uroot -prootdev123 nearslot <<'SQL' 2>&1 | head -3
INSERT IGNORE INTO user (id, nickname, password, level, deleted) VALUES (1001, 'testuser', 'pass12345', 5, 0);
INSERT IGNORE INTO user_phone (id, user_id, phone) VALUES (1, 1001, '13800138000');
INSERT IGNORE INTO shop (id, name, type_id, x, y, address) VALUES (1, '静心养生馆', 1, 120.15, 30.28, '杭州市西湖区文三路 100 号');
INSERT IGNORE INTO technician (id, shop_id, name, level) VALUES (2001, 1, '张师傅', 5);
INSERT IGNORE INTO time_slot (id, shop_id, technician_id, starts_at, ends_at, tz_id, capacity, status, version, min_level) VALUES (3001, 1, 2001, '2026-12-01 10:00:00', '2026-12-01 11:00:00', 'Asia/Shanghai', 1, 1, 0, 0);
SQL
echo "=== 校验 ==="
docker exec nearslot-mysql-test mysql -uroot -prootdev123 -N -e "SELECT CONCAT('shops=', (SELECT COUNT(*) FROM nearslot.shop), ' slots=', (SELECT COUNT(*) FROM nearslot.time_slot));" 2>&1 | head -2