-- 迁移：time_slot 增加会员等级门槛（FR-1.4 等级校验）
-- 回滚：ALTER TABLE time_slot DROP COLUMN min_level;
ALTER TABLE time_slot
  ADD COLUMN min_level INT NOT NULL DEFAULT 0 COMMENT '预约所需最低会员等级，0=无门槛';
