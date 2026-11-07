CREATE TABLE outbound_webhook_destination (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    provider VARCHAR(16) NOT NULL,
    name VARCHAR(80) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    url_ciphertext VARCHAR(4096) CHARACTER SET ascii NOT NULL,
    secret_ciphertext VARCHAR(1024) CHARACTER SET ascii NULL,
    INDEX ix_outbound_webhook_owner (user_id, enabled, id),
    CONSTRAINT fk_outbound_webhook_owner FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
);

CREATE TABLE outbound_webhook_test_limit (
    user_id INT NOT NULL PRIMARY KEY,
    next_allowed_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_outbound_webhook_test_owner FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
);
