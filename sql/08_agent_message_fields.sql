-- 缺口 G5：agent_message 补语义字段（不建新表，走增量）
USE nearslot;

SET @c1 := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='nearslot' AND table_name='agent_message' AND column_name='seq');
SET @sql := IF(@c1=0, 'ALTER TABLE agent_message ADD COLUMN seq INT NOT NULL DEFAULT 0 COMMENT ''回合内事件序号''', 'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @c2 := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='nearslot' AND table_name='agent_message' AND column_name='plan_json');
SET @sql := IF(@c2=0, 'ALTER TABLE agent_message ADD COLUMN plan_json TEXT NULL COMMENT ''PLAN 阶段步骤（JSON）''', 'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SET @c3 := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema='nearslot' AND table_name='agent_message' AND column_name='trace_id');
SET @sql := IF(@c3=0, 'ALTER TABLE agent_message ADD COLUMN trace_id BIGINT NULL COMMENT ''Langfuse trace（ADR-023）''', 'SELECT 1');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;

SELECT 'agent_message cols' AS item, COUNT(*) AS cnt FROM information_schema.columns
  WHERE table_schema='nearslot' AND table_name='agent_message';
