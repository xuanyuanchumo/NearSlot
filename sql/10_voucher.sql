-- FR-6.1 履约核销（业务域澄清 §4 的 P0 缺口）
--
-- 为什么必须有：当前链路「支付 → CONFIRMED」之后就没有下一步了，
-- 订单永远停在「已确认」，商家无法确认服务已完成，用户也无法证明履约。
-- 核销码是本地生活"预付 + 到店/上门"模式的履约凭证（business-scope-research.md §105）。
--
-- 设计要点：
-- 1. 一单一码：uk_voucher_order 保证不会重复发码；
-- 2. 码值不放用户 ID 等可枚举信息，用随机串（防遍历猜码核销）；
-- 3. 核销是**状态机迁移**（CONFIRMED → DONE），不是删记录——保留履约审计；
-- 4. 过期由 slot.ends_at 推导，不额外落过期时间字段（避免两处维护同一事实）。
USE nearslot;

CREATE TABLE IF NOT EXISTS appointment_voucher (
  id BIGINT PRIMARY KEY COMMENT '雪花ID',
  order_id BIGINT NOT NULL COMMENT '预约单号',
  slot_id BIGINT NOT NULL COMMENT '时段ID（冗余，便于按履约日统计）',
  shop_id BIGINT NOT NULL COMMENT '店铺ID（核销鉴权按店铺隔离）',
  user_id BIGINT NOT NULL COMMENT '持码用户',
  code VARCHAR(32) NOT NULL COMMENT '核销码（随机串，防枚举）',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1待核销 2已核销 3已作废',
  redeemed_by BIGINT NULL COMMENT '核销人（店员/平台）',
  redeemed_at DATETIME NULL COMMENT '核销时间',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_voucher_order (order_id),
  UNIQUE KEY uk_voucher_code (code),
  KEY idx_voucher_shop_status (shop_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='履约核销码';

SELECT 'appointment_voucher' AS table_name, COUNT(*) AS cols
FROM information_schema.columns
WHERE table_schema='nearslot' AND table_name='appointment_voucher';
