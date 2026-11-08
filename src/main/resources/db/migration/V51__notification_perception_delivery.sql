CREATE TABLE notification_perception_delivery (
    event_id BIGINT NOT NULL,
    channel_code VARCHAR(12) NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    due_at DATETIME(6) NOT NULL,
    lease_token CHAR(36) NULL,
    PRIMARY KEY(event_id,channel_code),
    INDEX ix_perception_delivery_due(status,due_at),
    CONSTRAINT fk_perception_delivery_event FOREIGN KEY(event_id) REFERENCES alert_event(id) ON DELETE CASCADE
);

CREATE TABLE notification_perception_strength (
    user_id INT NOT NULL,
    channel_code VARCHAR(12) NOT NULL,
    strength VARCHAR(16) NOT NULL,
    PRIMARY KEY(user_id,channel_code),
    CONSTRAINT fk_perception_strength_user FOREIGN KEY(user_id) REFERENCES user(id) ON DELETE CASCADE
);
