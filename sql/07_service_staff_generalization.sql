-- ADR-021：直接破坏性改列（幂等版，可重复执行）
-- 依据 docs/standards/breaking-change-policy.md：三问全否 -> 直接改，重建数据，不留兼容层
USE nearslot;

SET @has_old := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='nearslot' AND table_name='time_slot' AND column_name='technician_id');
SET @has_new := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='nearslot' AND table_name='time_slot' AND column_name='service_staff_id');

-- 老库：technician_id 存在 -> 直接改名
SET @sql := IF(@has_old=1,
  'ALTER TABLE time_slot CHANGE COLUMN technician_id service_staff_id BIGINT NULL COMMENT ''履约执行者''',
  'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- 过渡库：两列并存（我之前过度设计的产物）-> 删新列再改名
SET @both := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='nearslot' AND table_name='time_slot' AND column_name IN ('technician_id','service_staff_id'));
SET @sql := IF(@both=2,
  'ALTER TABLE time_slot DROP COLUMN service_staff_id',
  'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- appointment_order 同理
SET @has_old2 := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='nearslot' AND table_name='appointment_order' AND column_name='technician_id');
SET @sql := IF(@has_old2=1,
  'ALTER TABLE appointment_order CHANGE COLUMN technician_id service_staff_id BIGINT NULL COMMENT ''履约执行者''',
  'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @both2 := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='nearslot' AND table_name='appointment_order' AND column_name IN ('technician_id','service_staff_id'));
SET @sql := IF(@both2=2,
  'ALTER TABLE appointment_order DROP COLUMN service_staff_id',
  'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;


-- ADR-020/022：履约项类型与准备提前量（幂等）
SET @c1 := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='nearslot' AND table_name='service_item' AND column_name='item_type');
SET @sql := IF(@c1=0, 'ALTER TABLE service_item ADD COLUMN item_type VARCHAR(16) NOT NULL DEFAULT ''SERVICE''', 'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @c2 := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='nearslot' AND table_name='service_item' AND column_name='lead_time_min');
SET @sql := IF(@c2=0, 'ALTER TABLE service_item ADD COLUMN lead_time_min INT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @c3 := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='nearslot' AND table_name='appointment_order' AND column_name='fulfillment_mode');
SET @sql := IF(@c3=0, 'ALTER TABLE appointment_order ADD COLUMN fulfillment_mode VARCHAR(20) NOT NULL DEFAULT ''DOOR_TO_DOOR''', 'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- 验证
SELECT 'time_slot.service_staff_id' AS col, COUNT(*) AS present FROM information_schema.columns
  WHERE table_schema='nearslot' AND table_name='time_slot' AND column_name='service_staff_id';
