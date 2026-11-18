-- 迁移：用户角色与店铺归属（多租户平台 P0 安全）
USE nearslot;

CREATE TABLE IF NOT EXISTS user_role (
  user_id BIGINT PRIMARY KEY COMMENT '用户ID',
  role VARCHAR(20) NOT NULL COMMENT 'CONSUMER/STAFF/MERCHANT/PLATFORM',
  shop_id BIGINT NULL COMMENT '归属店铺ID；NULL=平台级',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_role_shop (role, shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色与数据归属';
-- 种子：1001 同时是消费者与平台管理员（演示用，生产需拆分）
INSERT IGNORE INTO user_role (user_id, role, shop_id) VALUES (1001, 'PLATFORM', NULL), (1001, 'CONSUMER', NULL);
