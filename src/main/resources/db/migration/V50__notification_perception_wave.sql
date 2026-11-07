CREATE TABLE notification_perception_wave (
    event_id BIGINT NOT NULL,
    stage SMALLINT UNSIGNED NOT NULL,
    channel_codes VARCHAR(64) NOT NULL,
    state VARCHAR(12) NOT NULL,
    due_at DATETIME(6) NULL,
    PRIMARY KEY (event_id, stage),
    INDEX ix_perception_wave_due (state, due_at, event_id),
    CONSTRAINT fk_perception_wave_event FOREIGN KEY (event_id) REFERENCES alert_event(id) ON DELETE CASCADE
);
