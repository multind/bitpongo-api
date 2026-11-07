CREATE TABLE outbound_webhook_delivery (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    destination_id BIGINT NOT NULL,
    event_key CHAR(64) CHARACTER SET ascii NOT NULL,
    message VARCHAR(512) NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    lease_until DATETIME(6) NULL,
    lease_token CHAR(36) CHARACTER SET ascii NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_webhook_event (destination_id, event_key),
    INDEX ix_webhook_dispatch (status, next_attempt_at, id),
    INDEX ix_webhook_lease (status, lease_until, id),
    INDEX ix_webhook_retention (status, updated_at),
    CONSTRAINT fk_webhook_delivery_destination FOREIGN KEY (destination_id)
        REFERENCES outbound_webhook_destination(id) ON DELETE CASCADE
);
