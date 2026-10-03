CREATE TABLE webhook_rule (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id INT NOT NULL,
 name VARCHAR(100) NOT NULL,
 description VARCHAR(500) NOT NULL,
 keywords VARCHAR(500) NOT NULL,
 allowed_ips VARCHAR(1000) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
 public_id CHAR(36) NOT NULL,
 secret_cipher TEXT NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_webhook_public(public_id),
 INDEX ix_webhook_owner(user_id,id),
 FOREIGN KEY(user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB;
CREATE TABLE webhook_receipt (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 webhook_id BIGINT NOT NULL,
 nonce VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 received_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_webhook_nonce(webhook_id,nonce),
 INDEX ix_webhook_receipt_time(received_at),
 FOREIGN KEY(webhook_id) REFERENCES webhook_rule(id) ON DELETE CASCADE
) ENGINE=InnoDB;
