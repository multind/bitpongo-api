ALTER TABLE alert_event
    ADD COLUMN confirmed_at DATETIME(6) NULL;

CREATE TABLE notification_escalation (
    event_key VARCHAR(191) NOT NULL,
    user_id INT NOT NULL,
    due_at DATETIME(6) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    channel VARCHAR(32) NULL,
    attempts INT NOT NULL DEFAULT 0,
    lease_token CHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    last_error VARCHAR(512) NULL,
    escalated_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY(event_key),
    KEY ix_notification_escalation_due(status, due_at, lease_until),
    CONSTRAINT fk_notification_escalation_user FOREIGN KEY(user_id) REFERENCES user(id) ON DELETE CASCADE,
    CONSTRAINT fk_notification_escalation_event FOREIGN KEY(event_key) REFERENCES alert_event(event_key) ON DELETE CASCADE
) ENGINE=InnoDB;