#!/usr/bin/env bash
set -e
M="D:/MySQL/MySQL Server 8.0/bin/mysql"
cd "D:/Projects/TraeProjects/Studying/NearSlot"
echo "=== 删库重建（破坏性变更 SOP）==="
"$M" -uroot -h127.0.0.1 -P3306 -e "DROP DATABASE IF EXISTS nearslot;"
"$M" -uroot -h127.0.0.1 -P3306 < sql/1_create_database.sql
"$M" -uroot -h127.0.0.1 -P3306 < sql/2_business.sql
echo "=== 应用迁移 ==="
for f in sql/3_*.sql sql/4_*.sql sql/5_*.sql sql/6_*.sql; do
  [ -f "$f" ] && "$M" -uroot -h127.0.0.1 -P3306 nearslot < "$f" 2>/dev/null || true
done
# 7 是破坏性改名（直接 CHANGE COLUMN），放在最后
"$M" -uroot -h127.0.0.1 -P3306 < sql/7_service_staff_generalization.sql >/dev/null 2>&1
echo "=== 种子数据（新列名 service_staff_id）==="
"$M" -uroot -h127.0.0.1 -P3306 nearslot <<'SQL' 2>/dev/null
INSERT INTO user (id, nickname, password, level, deleted) VALUES (1001, 'testuser', 'pass12345', 5, 0);
INSERT INTO user_phone (id, user_id, phone) VALUES (1, 1001, '13800138000');
INSERT INTO shop (id, name, type_id, x, y, address) VALUES (1, '静心养生馆', 1, 120.15, 30.28, '杭州市西湖区文三路 100 号');
INSERT INTO shop (id, name, type_id, x, y, address) VALUES (2, '悦享足道', 1, 120.16, 30.29, '杭州市西湖区古翠路 88 号');
INSERT INTO technician (id, shop_id, name, level) VALUES (2001, 1, '张师傅', 5);
INSERT INTO time_slot (id, shop_id, service_staff_id, starts_at, ends_at, tz_id, capacity, status, version, min_level) VALUES (3001, 1, 2001, '2026-12-01 10:00:00', '2026-12-01 11:00:00', 'Asia/Shanghai', 1, 1, 0, 0);
INSERT INTO time_slot (id, shop_id, service_staff_id, starts_at, ends_at, tz_id, capacity, status, version, min_level) VALUES (3002, 1, 2001, '2026-12-01 14:00:00', '2026-12-01 15:00:00', 'Asia/Shanghai', 1, 1, 0, 8);
INSERT INTO user_role (user_id, role, shop_id) VALUES (1001, 'PLATFORM', NULL);
SQL
echo "=== 校验 ==="
"$M" -uroot -h127.0.0.1 -P3306 -N -e "SELECT CONCAT('staff_col=',(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='nearslot' AND table_name='time_slot' AND column_name='service_staff_id'),' old_col=',(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='nearslot' AND table_name='time_slot' AND column_name='technician_id'),' slots=',(SELECT COUNT(*) FROM nearslot.time_slot));"
