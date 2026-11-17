-- H2 冒烟验证用最小 schema（verify profile only）
-- 说明：非生产 DDL（生产见 sql/2_business.sql，含分片/索引/约束）。
-- 目的：让本地 verify profile 能真实执行登录/抢约链路，而非被"表不存在"掩盖。

CREATE TABLE IF NOT EXISTS user (
  id BIGINT PRIMARY KEY,
  nickname VARCHAR(64) DEFAULT '',
  password VARCHAR(128) DEFAULT '',
  level TINYINT DEFAULT 1,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS user_phone (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  phone VARCHAR(20) NOT NULL
);

CREATE TABLE IF NOT EXISTS shop (
  id BIGINT PRIMARY KEY,
  name VARCHAR(128),
  type_id BIGINT DEFAULT 0,
  x DOUBLE DEFAULT 0,
  y DOUBLE DEFAULT 0,
  address VARCHAR(255) DEFAULT '',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS technician (
  id BIGINT PRIMARY KEY, shop_id BIGINT, name VARCHAR(64), level TINYINT DEFAULT 1,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS time_slot (
  id BIGINT PRIMARY KEY,
  shop_id BIGINT NOT NULL,
  technician_id BIGINT NOT NULL,
  service_item_id BIGINT DEFAULT 0,
  starts_at TIMESTAMP NOT NULL,
  ends_at TIMESTAMP NOT NULL,
  tz_id VARCHAR(64) DEFAULT 'Asia/Shanghai',
  capacity INT DEFAULT 1,
  status TINYINT DEFAULT 1,
  version INT DEFAULT 0,
  min_level INT DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS appointment_order (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  slot_id BIGINT NOT NULL,
  shop_id BIGINT NOT NULL,
  technician_id BIGINT NOT NULL,
  status TINYINT DEFAULT 1,
  amount_cent INT DEFAULT 0,
  hold_expires_at TIMESTAMP NULL,
  reconcile_status TINYINT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS appointment_order_router (
  order_id BIGINT PRIMARY KEY, user_id BIGINT, slot_id BIGINT,
  ds_name VARCHAR(16), tbl_suffix VARCHAR(8), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS appointment_reconcile_log (
  id BIGINT PRIMARY KEY, order_id BIGINT NOT NULL, log_type TINYINT, result TINYINT,
  before_qty INT DEFAULT 0, change_qty INT DEFAULT 0, after_qty INT DEFAULT 0,
  trace_id BIGINT NULL, remark VARCHAR(255) DEFAULT '', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS rollback_failure_log (
  id BIGINT PRIMARY KEY, order_id BIGINT, reason VARCHAR(255) DEFAULT '',
  payload CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 验证用种子数据（1 个用户 / 1 个服务商 / 1 个时段）
MERGE INTO user (id, nickname, password, level) KEY(id) VALUES (1001, 'testuser', 'pass12345', 5);
MERGE INTO user_phone (id, user_id, phone) KEY(id) VALUES (1, 1001, '13800138000');
-- 服务商（3 家，供前端列表页展示）
MERGE INTO shop (id, name, type_id, x, y, address) KEY(id) VALUES (1, '静心养生馆', 1, 120.15, 30.28, '杭州市西湖区文三路 100 号');
MERGE INTO shop (id, name, type_id, x, y, address) KEY(id) VALUES (2, '悦享足道', 1, 120.16, 30.29, '杭州市西湖区古翠路 88 号');
MERGE INTO shop (id, name, type_id, x, y, address) KEY(id) VALUES (3, '匠心美容 SPA', 2, 120.14, 30.27, '杭州市上城区延安路 200 号');
-- 技师
MERGE INTO technician (id, shop_id, name, level) KEY(id) VALUES (2001, 1, '张师傅', 5);
MERGE INTO technician (id, shop_id, name, level) KEY(id) VALUES (2002, 1, '李师傅', 4);
MERGE INTO technician (id, shop_id, name, level) KEY(id) VALUES (2003, 2, '王师傅', 3);
MERGE INTO technician (id, shop_id, name, level) KEY(id) VALUES (2004, 3, '赵师傅', 5);
-- 时段 3001：无等级门槛；时段 3002：需 Lv8（用于验证等级闸）
MERGE INTO time_slot (id, shop_id, technician_id, starts_at, ends_at, capacity, status, min_level)
  KEY(id) VALUES (3001, 1, 2001, TIMESTAMP '2026-12-01 10:00:00', TIMESTAMP '2026-12-01 11:00:00', 1, 1, 0);
MERGE INTO time_slot (id, shop_id, technician_id, starts_at, ends_at, capacity, status, min_level)
  KEY(id) VALUES (3002, 1, 2001, TIMESTAMP '2026-12-01 14:00:00', TIMESTAMP '2026-12-01 15:00:00', 1, 1, 8);
