-- 迁移：技师技能关联 + 技师评分/状态（业务域澄清，docs/architecture/business-domain.md）
-- 回滚见每条 ALTER 下方注释

CREATE TABLE IF NOT EXISTS technician_skill (
  id BIGINT PRIMARY KEY COMMENT '雪花ID',
  technician_id BIGINT NOT NULL COMMENT '技师ID',
  service_item_id BIGINT NOT NULL COMMENT '服务项ID',
  proficiency TINYINT NOT NULL DEFAULT 3 COMMENT '熟练度 1-5',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_tech_skill (technician_id, service_item_id),
  KEY idx_skill_item (service_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技师-技能关联';
-- 回滚：DROP TABLE technician_skill;

ALTER TABLE technician
  ADD COLUMN avatar VARCHAR(255) NOT NULL DEFAULT '' COMMENT '头像',
  ADD COLUMN work_status TINYINT NOT NULL DEFAULT 1 COMMENT '在岗状态 1在岗 2请假 3离职',
  ADD COLUMN rating_avg INT NOT NULL DEFAULT 500 COMMENT '平均评分(放大100倍)',
  ADD COLUMN rating_count INT NOT NULL DEFAULT 0 COMMENT '评价数';
-- 回滚：ALTER TABLE technician DROP COLUMN avatar, DROP COLUMN work_status, DROP COLUMN rating_avg, DROP COLUMN rating_count;

ALTER TABLE service_item
  ADD COLUMN duration_min INT NOT NULL DEFAULT 60 COMMENT '服务时长(分钟)',
  ADD COLUMN price_cent INT NOT NULL DEFAULT 0 COMMENT '价格(分)';
-- 回滚：ALTER TABLE service_item DROP COLUMN duration_min, DROP COLUMN price_cent;
