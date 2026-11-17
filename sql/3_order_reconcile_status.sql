-- 迁移脚本：appointment_order 增加对账状态列（幂等）
-- 变更原因：Phase 2 对账任务需要标记每笔订单的对账结论
-- 回滚：ALTER TABLE appointment_order DROP COLUMN reconcile_status;
ALTER TABLE appointment_order
  ADD COLUMN reconcile_status TINYINT NULL COMMENT '对账状态 1待对账 2不一致 4已一致';
