ALTER TABLE plan_fire_execution
    ADD COLUMN result_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN' AFTER scheduled_fire_time,
    ADD COLUMN reason VARCHAR(64) NULL AFTER result_status,
    ADD COLUMN symbols VARCHAR(1024) NOT NULL DEFAULT '' AFTER reason,
    ADD COLUMN completed_at DATETIME NULL AFTER symbols,
    ADD INDEX idx_plan_fire_execution_history (plan_id, scheduled_fire_time, id);
