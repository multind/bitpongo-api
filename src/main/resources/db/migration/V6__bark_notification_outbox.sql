CREATE TABLE user_bark_setting (
    user_id INT NOT NULL,
    server_url VARCHAR(255) NOT NULL,
    device_key_ciphertext VARCHAR(1024) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    locale VARCHAR(16) NOT NULL DEFAULT 'zh-CN',
    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_bark_setting_user FOREIGN KEY (user_id) REFERENCES user(id)
) ENGINE=InnoDB;

CREATE TABLE notification_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_type VARCHAR(48) NOT NULL,
    recipient_type VARCHAR(16) NOT NULL,
    user_id INT NULL,
    title_key VARCHAR(96) NOT NULL,
    body_payload JSON NOT NULL,
    dedupe_key VARCHAR(191) NOT NULL,
    priority VARCHAR(24) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NOT NULL,
    lease_until DATETIME NULL,
    last_error VARCHAR(512) NULL,
    created_at DATETIME NOT NULL,
    sent_at DATETIME NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_outbox_dedupe (dedupe_key),
    KEY ix_notification_outbox_dispatch (status, next_attempt_at, lease_until),
    KEY ix_notification_outbox_user (user_id, status),
    CONSTRAINT fk_notification_outbox_user FOREIGN KEY (user_id) REFERENCES user(id)
) ENGINE=InnoDB;
