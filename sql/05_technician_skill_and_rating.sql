-- 迁移：技师技能关联 + 技师评分/状态（业务域澄清，docs/architecture/business-domain.md）
-- **2026-11-18 幂等修复**：原 ALTER 无守卫，Docker 全新初始化时若 02_business.sql
-- 已含这些列会报 1060 导致首次启动失败。现统一用 information_schema 守卫。
-- 回滚见每条 ALTER 下方注释
USE nearslot;

CREATE TABLE IF NOT EXISTS technician_skill (
  id BIGINT PRIMARY KEY COMMENT '雪花ID',
  technician_id BIGINT NOT NULL COMMENT '技师ID',
  service_item_id BIGINT NOT NULL COMMENT '服务项ID',
  proficiency TINYINT NOT NULL DEFAULT 3 COMMENT '熟练度 1-5',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_tech_item (technician_id, service_item_id),
  KEY idx_ts_item (service_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技师-服务项技能关联';

-- technician：头像（回滚 DROP COLUMN avatar）
SET @c := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='nearslot' AND table_name='technician' AND column_name='avatar');
SET @sql := IF(@c=0, 'ALTER TABLE technician ADD COLUMN avatar VARCHAR(255) NOT NULL DEFAULT \'\' COMMENT \'头像\'', 'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- technician：在岗状态（回滚 DROP COLUMN work_status）
SET @c := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='nearslot' AND table_name='technician' AND column_name='work_status');
SET @sql := IF(@c=0, 'ALTER TABLE technician ADD COLUMN work_status TINYINT NOT NULL DEFAULT 1 COMMENT \'在岗状态 1在岗 2请假 3离职\'', 'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- technician：平均评分（回滚 DROP COLUMN rating_avg）
SET @c := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='nearslot' AND table_name='technician' AND column_name='rating_avg');
SET @sql := IF(@c=0, 'ALTER TABLE technician ADD COLUMN rating_avg INT NOT NULL DEFAULT 500 COMMENT \'平均评分(放大100倍)\'', 'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- technician：评价数（回滚 DROP COLUMN rating_count）
SET @c := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='nearslot' AND table_name='technician' AND column_name='rating_count');
SET @sql := IF(@c=0, 'ALTER TABLE technician ADD COLUMN rating_count INT NOT NULL DEFAULT 0 COMMENT \'评价数\'', 'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- service_item：服务时长（回滚 DROP COLUMN duration_min）
SET @c := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='nearslot' AND table_name='service_item' AND column_name='duration_min');
SET @sql := IF(@c=0, 'ALTER TABLE service_item ADD COLUMN duration_min INT NOT NULL DEFAULT 60 COMMENT \'服务时长(分钟)\'', 'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- service_item：价格（回滚 DROP COLUMN price_cent）
SET @c := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='nearslot' AND table_name='service_item' AND column_name='price_cent');
SET @sql := IF(@c=0, 'ALTER TABLE service_item ADD COLUMN price_cent INT NOT NULL DEFAULT 0 COMMENT \'价格(分)\'', 'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

