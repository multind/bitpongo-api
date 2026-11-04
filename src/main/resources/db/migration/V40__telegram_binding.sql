ALTER TABLE user_notification_channel
    ADD COLUMN telegram_chat_id BIGINT NULL,
    ADD UNIQUE KEY uq_telegram_chat (telegram_chat_id);

CREATE TABLE telegram_binding_challenge (
    user_id INT NOT NULL PRIMARY KEY,
    code_hash CHAR(64) NOT NULL UNIQUE,
    expires_at DATETIME(6) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
);
