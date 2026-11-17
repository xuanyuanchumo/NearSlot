-- 审计列补齐（docs/database/schema.md §1：每表必带 created_at / updated_at）
--
-- 背景（2026-11-17 实测踩坑）：实体继承 BaseEntity 后，MyBatis-Plus 会把
-- created_at/updated_at 写进每条 SELECT/INSERT；而 technician 只有 created_at、
-- service_item 两者都没有 —— 直接导致 `Unknown column updated_at in field list`，
-- 技师分页与技能绑定接口全部 500。
--
-- 这里按规范把列补齐（而不是把 BaseEntity 从实体上摘掉）：
-- 规范要求这两列存在，缺列本身才是缺陷；补列后自动填充才真正生效。
USE nearslot;

-- technician: 缺 updated_at
SET @c := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='nearslot' AND table_name='technician' AND column_name='updated_at');
SET @sql := IF(@c=0,
  'ALTER TABLE technician ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP',
  'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- service_item: 缺 created_at 与 updated_at
SET @c := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='nearslot' AND table_name='service_item' AND column_name='created_at');
SET @sql := IF(@c=0,
  'ALTER TABLE service_item ADD COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP',
  'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='nearslot' AND table_name='service_item' AND column_name='updated_at');
SET @sql := IF(@c=0,
  'ALTER TABLE service_item ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP',
  'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SELECT table_name, group_concat(column_name) AS audit_cols
FROM information_schema.columns
WHERE table_schema='nearslot' AND column_name IN ('created_at','updated_at')
  AND table_name IN ('technician','service_item','shop','time_slot','appointment_order','appointment_voucher')
GROUP BY table_name;
