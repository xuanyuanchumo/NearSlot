#!/usr/bin/env bash
MYSQL="D:/MySQL/MySQL Server 8.0/bin/mysql"
echo "=== 种子数据（密码 BCrypt 由应用登录时自动升级）==="
"$MYSQL" -uroot -h127.0.0.1 -P3306 nearslot <<'SQL' 2>&1 | head -5
INSERT IGNORE INTO user (id, nickname, password, level, deleted) VALUES (1001, 'testuser', 'pass12345', 5, 0);
INSERT IGNORE INTO user_phone (id, user_id, phone) VALUES (1, 1001, '13800138000');
INSERT IGNORE INTO shop (id, name, type_id, x, y, address) VALUES (1, '静心养生馆', 1, 120.15, 30.28, '杭州市西湖区文三路 100 号');
INSERT IGNORE INTO shop (id, name, type_id, x, y, address) VALUES (2, '悦享足道', 1, 120.16, 30.29, '杭州市西湖区古翠路 88 号');
INSERT IGNORE INTO shop (id, name, type_id, x, y, address) VALUES (3, '匠心美容 SPA', 2, 120.14, 30.27, '杭州市上城区延安路 200 号');
INSERT IGNORE INTO technician (id, shop_id, name, level) VALUES (2001, 1, '张师傅', 5);
INSERT IGNORE INTO technician (id, shop_id, name, level) VALUES (2002, 1, '李师傅', 4);
INSERT IGNORE INTO technician (id, shop_id, name, level) VALUES (2003, 2, '王师傅', 3);
INSERT IGNORE INTO technician (id, shop_id, name, level) VALUES (2004, 3, '赵师傅', 5);
INSERT IGNORE INTO time_slot (id, shop_id, technician_id, starts_at, ends_at, tz_id, capacity, status, version, min_level) VALUES (3001, 1, 2001, '2026-12-01 10:00:00', '2026-12-01 11:00:00', 'Asia/Shanghai', 1, 1, 0, 0);
INSERT IGNORE INTO time_slot (id, shop_id, technician_id, starts_at, ends_at, tz_id, capacity, status, version, min_level) VALUES (3002, 1, 2001, '2026-12-01 14:00:00', '2026-12-01 15:00:00', 'Asia/Shanghai', 1, 1, 0, 8);
SQL
echo "=== 校验 ==="
"$MYSQL" -uroot -h127.0.0.1 -P3306 -N -e "SELECT CONCAT(\"shops=\", (SELECT COUNT(*) FROM nearslot.shop), \" techs=\", (SELECT COUNT(*) FROM nearslot.technician), \" slots=\", (SELECT COUNT(*) FROM nearslot.time_slot), \" users=\", (SELECT COUNT(*) FROM nearslot.user));"