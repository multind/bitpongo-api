-- Bitpongo timezone rollout audit (read-only).
-- Run against a backup or a read-only production connection before and after deployment.

SELECT id, cron, schedule_timezone
FROM strategy
ORDER BY id;

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
