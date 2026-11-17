-- 缺口（2026-11-17 审计，Blocker）：取消后无法再次预约同一时段
--
-- 现象：抢约 → 取消（状态 CANCELLED）→ 再抢同一 slot，DB 抛 DuplicateKey，
--       被 WebExceptionAdvice 映射成 50102「重复预约（一人一时段）」。已实测复现。
--
-- 根因：uq_user_slot(user_id, slot_id) 是**无条件**唯一约束，
--       它把「曾经预约过」当成「当前占用」，而取消/超时回收只是改状态、不删行，
--       于是该用户对这个时段被永久锁死。
--
-- 修复思路（保留 DB 级最后防线，而不是简单删掉约束）：
--   用**生成列 + 条件唯一索引**：只有活跃状态（1 待支付 / 2 已确认）才生成键值，
--   其余状态生成 NULL；MySQL 的唯一索引允许多行 NULL，等于实现了「部分唯一索引」。
--   这样既保住「同一用户同一时段不能有两个活跃订单」的硬约束，
--   又允许取消后重新预约。
USE nearslot;

-- 1) 若旧的生成列/索引已存在则跳过（幂等）
SET @has_gen := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='nearslot' AND table_name='appointment_order'
    AND column_name='active_user_slot');
SET @sql := IF(@has_gen=0,
  'ALTER TABLE appointment_order ADD COLUMN active_user_slot VARCHAR(64)
     GENERATED ALWAYS AS (CASE WHEN status IN (1,2)
       THEN CONCAT(user_id, \':\', slot_id) ELSE NULL END) STORED
     COMMENT \'活跃占用键：仅待支付/已确认生成，取消后置 NULL 以允许重新预约\' ',
  'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) 条件唯一索引
SET @has_idx := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema='nearslot' AND table_name='appointment_order'
    AND index_name='uq_active_user_slot');
SET @sql := IF(@has_idx=0,
  'ALTER TABLE appointment_order ADD UNIQUE KEY uq_active_user_slot (active_user_slot)',
  'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

-- 3) 删除无条件唯一约束（它才是取消后被锁死的直接原因）
SET @has_old := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema='nearslot' AND table_name='appointment_order'
    AND index_name='uq_user_slot');
SET @sql := IF(@has_old>0,
  'ALTER TABLE appointment_order DROP INDEX uq_user_slot',
  'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SELECT 'appointment_order 索引' AS item, index_name, non_unique
FROM information_schema.statistics
WHERE table_schema='nearslot' AND table_name='appointment_order'
GROUP BY index_name, non_unique;
