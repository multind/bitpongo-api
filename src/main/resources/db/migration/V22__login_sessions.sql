CREATE TABLE login_session (
    id CHAR(36) NOT NULL PRIMARY KEY,
    user_id INT NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    INDEX ix_login_session_user (user_id, expires_at),
    INDEX ix_login_session_expiry (expires_at),
    CONSTRAINT fk_login_session_user FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
);
