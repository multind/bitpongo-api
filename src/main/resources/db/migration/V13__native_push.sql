CREATE TABLE user_push_setting (
    user_id INT NOT NULL PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    trade_succeeded BOOLEAN NOT NULL DEFAULT TRUE,
    trade_failed BOOLEAN NOT NULL DEFAULT TRUE,
    plan_alerts BOOLEAN NOT NULL DEFAULT TRUE,
    system_alerts BOOLEAN NOT NULL DEFAULT TRUE,
    sound_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    locale VARCHAR(16) NOT NULL DEFAULT 'zh-CN',
    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    last_test_at DATETIME(6) NULL,
    CONSTRAINT fk_push_setting_user FOREIGN KEY(user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Serializes token transfers across installation IDs. No token is ever included in audit/error text.
CREATE TABLE push_registration_lock (id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO push_registration_lock VALUES (1);

CREATE TABLE push_device (
    installation_id CHAR(36) NOT NULL PRIMARY KEY,
    user_id INT NOT NULL,
    token VARCHAR(4096) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    binding_version CHAR(36) NOT NULL,
    platform VARCHAR(8) NOT NULL,
    permission VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    app_version VARCHAR(64) NOT NULL,
    locale VARCHAR(16) NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_push_token(token_hash),
    KEY ix_push_device_user(user_id),
    KEY ix_push_device_seen(last_seen_at),
    CONSTRAINT fk_push_device_user FOREIGN KEY(user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE push_delivery (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id CHAR(64) NOT NULL,
    dedupe_key CHAR(64) NOT NULL,
    user_id INT NOT NULL,
    installation_id CHAR(36) NOT NULL,
    binding_version CHAR(36) NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    message JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    lease_token CHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    last_error VARCHAR(48) NULL,
    UNIQUE KEY uk_push_delivery(dedupe_key),
    KEY ix_push_due(status,next_attempt_at,lease_until),
    KEY ix_push_delivery_device(installation_id),
    KEY ix_push_delivery_retention(updated_at),
    CONSTRAINT fk_push_delivery_user FOREIGN KEY(user_id) REFERENCES user(id) ON DELETE CASCADE,
    CONSTRAINT fk_push_delivery_device FOREIGN KEY(installation_id) REFERENCES push_device(installation_id) ON DELETE CASCADE
) ENGINE=InnoDB;
