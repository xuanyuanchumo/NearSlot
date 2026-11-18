-- 迁移：time_slot 增加会员等级门槛（FR-1.4 等级校验）（**幂等**）
-- 回滚：ALTER TABLE time_slot DROP COLUMN min_level;
USE nearslot;

-- time_slot：会员等级门槛列
SET @c := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='nearslot' AND table_name='time_slot' AND column_name='min_level');
SET @sql := IF(@c=0, 'ALTER TABLE time_slot ADD COLUMN min_level INT NOT NULL DEFAULT 0 COMMENT \'预约所需最低会员等级，0=无门槛\'', 'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
