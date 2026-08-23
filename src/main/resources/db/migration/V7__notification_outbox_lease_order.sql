CREATE INDEX ix_notification_outbox_lease_order
    ON notification_outbox (
        priority,
        created_at,
        status,
        next_attempt_at,
        lease_until,
        id
    );
