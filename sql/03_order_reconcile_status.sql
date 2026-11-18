-- 迁移脚本：appointment_order 增加对账状态列（**幂等**：可重复执行）
-- 变更原因：Phase 2 对账任务需要标记每笔订单的对账结论
-- 回滚：ALTER TABLE appointment_order DROP COLUMN reconcile_status;
--
-- 为什么用 information_schema 守卫：MySQL 8 的 ADD COLUMN 没有 IF NOT EXISTS，
-- 直接执行在"列已存在"时会报 1060；而 Docker 全新初始化会按序重放所有脚本，
-- 若 02_business.sql 已含该列，本脚本必须能安全跳过（否则首次启动即失败）。
USE nearslot;

-- appointment_order：对账状态列
SET @c := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='nearslot' AND table_name='appointment_order' AND column_name='reconcile_status');
SET @sql := IF(@c=0, 'ALTER TABLE appointment_order ADD COLUMN reconcile_status TINYINT NULL COMMENT \'对账状态 1待对账 2不一致 4已一致\'', 'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
