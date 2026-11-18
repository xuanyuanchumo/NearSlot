-- 本地/联调种子数据（tests 与 scripts/dev 依赖的三角色账号）
-- 口令均为 pass12345（BCrypt 10 轮，哈希由项目自身的 BCryptPasswordEncoder 生成）
-- 仅用于 dev/verify 环境；生产不得执行本脚本。
USE nearslot;

INSERT INTO user (id, nickname, password, level, deleted) VALUES
  (1001, 'consumer', '$2a$10$/6gsJiy6up.gLDvwjrCRqO4p4f0bQpH3FA4G6zOrgde9L0Bi5sXtO', 5, 0),
  (1002, 'merchant', '$2a$10$/6gsJiy6up.gLDvwjrCRqO4p4f0bQpH3FA4G6zOrgde9L0Bi5sXtO', 5, 0),
  (1003, 'platform', '$2a$10$/6gsJiy6up.gLDvwjrCRqO4p4f0bQpH3FA4G6zOrgde9L0Bi5sXtO', 5, 0)
ON DUPLICATE KEY UPDATE password = VALUES(password), level = VALUES(level), deleted = 0;

INSERT INTO user_phone (user_id, phone) VALUES
  (1001, '13800138000'), (1002, '13900139000'), (1003, '13700137000')
ON DUPLICATE KEY UPDATE phone = VALUES(phone);

INSERT INTO user_role (user_id, role, shop_id) VALUES
  (1001, 'CONSUMER', NULL), (1002, 'MERCHANT', 1), (1003, 'PLATFORM', NULL)
ON DUPLICATE KEY UPDATE role = VALUES(role), shop_id = VALUES(shop_id);

-- 供 E2E 用的一个店 + 一个技师 + 一个服务项 + 一个时段
INSERT INTO shop (id, name, type_id, address, x, y) VALUES
  (1, '静心养生馆', 1, '文三路 100 号', 120.15, 30.28)
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO technician (id, shop_id, name, avatar, work_status, rating_avg, rating_count) VALUES
  (1, 1, '张师傅', '', 1, 500, 0)
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO service_item (id, shop_id, name, duration_min, price_cent) VALUES
  (1, 1, '肩颈推拿 60 分钟', 60, 19900)
ON DUPLICATE KEY UPDATE name = VALUES(name);
