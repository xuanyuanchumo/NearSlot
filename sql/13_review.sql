-- FR-12 评价与评分（履约驱动：只对已完成订单开放）
--
-- 为什么绑定 order_id 且唯一：
-- 1. 评价必须来自**真实履约**（ADR-026 同一原则），无订单不能评，杜绝刷分；
-- 2. uk_review_order 保证一单一评，防重复计分（并发下由唯一键兜底，而非应用层判断）。
-- 3. rating 存 1..5 整数；技师表 rating_avg 存**放大 100 倍**的整数（ADR 里已约定的表示）。
USE nearslot;

CREATE TABLE IF NOT EXISTS appointment_review (
  id BIGINT PRIMARY KEY COMMENT '雪花ID',
  order_id BIGINT NOT NULL COMMENT '履约订单（唯一，一单一评）',
  user_id BIGINT NOT NULL COMMENT '评价人',
  shop_id BIGINT NOT NULL COMMENT '归属店铺（便于按店聚合与商家回复鉴权）',
  technician_id BIGINT NULL COMMENT '被评技师（餐品预约可为空）',
  service_item_id BIGINT NULL COMMENT '服务项',
  rating TINYINT NOT NULL COMMENT '评分 1..5',
  content VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '评价正文',
  images VARCHAR(2048) NOT NULL DEFAULT '' COMMENT '逗号分隔图片URL',
  anonymous TINYINT NOT NULL DEFAULT 0 COMMENT '1=匿名展示',
  merchant_reply VARCHAR(1000) NULL COMMENT '商家回复',
  reply_at DATETIME NULL COMMENT '回复时间',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1正常 2隐藏',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_review_order (order_id),
  KEY idx_review_tech (technician_id, created_at),
  KEY idx_review_shop (shop_id, created_at),
  KEY idx_review_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服务评价（履约驱动）';

SELECT table_name, (SELECT COUNT(*) FROM information_schema.columns c
  WHERE c.table_schema='nearslot' AND c.table_name=t.table_name) AS cols
FROM information_schema.tables t
WHERE t.table_schema='nearslot' AND t.table_name='appointment_review';
