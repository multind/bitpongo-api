-- Bitpongo timezone rollout audit (read-only).
-- Run against a backup or a read-only production connection before and after deployment.

-- V11 adds strategy.schedule_timezone. Select it when present, otherwise emit
-- NULL so the same preflight also works immediately before the migration.
SET @has_schedule_timezone := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'strategy'
      AND COLUMN_NAME = 'schedule_timezone'
);
SET @strategy_audit_sql := IF(
    @has_schedule_timezone > 0,
    'SELECT id, cron, schedule_timezone FROM strategy ORDER BY id',
    'SELECT id, cron, NULL AS schedule_timezone FROM strategy ORDER BY id'
);
PREPARE strategy_audit FROM @strategy_audit_sql;
EXECUTE strategy_audit;
DEALLOCATE PREPARE strategy_audit;

SELECT id, status, next_time
FROM plan
WHERE status = 'active'
ORDER BY id;

SELECT TRIGGER_NAME, CRON_EXPRESSION, TIME_ZONE
FROM QRTZ_CRON_TRIGGERS
ORDER BY TRIGGER_NAME;

SELECT id,
       event_type,
       JSON_UNQUOTE(JSON_EXTRACT(body_payload, '$.scheduledAt')) AS scheduled_at,
       JSON_UNQUOTE(JSON_EXTRACT(body_payload, '$.occurredAt')) AS occurred_at,
       created_at,
       sent_at
FROM notification_outbox
ORDER BY id DESC
LIMIT 50;
