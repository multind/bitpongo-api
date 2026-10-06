ALTER TABLE user ADD COLUMN security_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE account_email_challenge (
    id CHAR(36) PRIMARY KEY,
    user_id INT NULL,
    purpose VARCHAR(16) NOT NULL,
    email VARCHAR(100) NOT NULL,
    code_hash CHAR(64) NOT NULL,
    security_version BIGINT NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    expires_at DATETIME(6) NOT NULL,
    consumed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_challenge_expiry (expires_at),
    INDEX idx_challenge_user (user_id),
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
);

CREATE TABLE account_security_rate (
    bucket CHAR(64) PRIMARY KEY,
    window_start DATETIME(6) NOT NULL,
    requests INT NOT NULL,
    last_sent_at DATETIME(6) NOT NULL
);
