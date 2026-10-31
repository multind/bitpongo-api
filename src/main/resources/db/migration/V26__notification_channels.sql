CREATE TABLE user_notification_channel (
    user_id INT NOT NULL PRIMARY KEY,
    email_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    sms_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    phone_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    dingtalk_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    feishu_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    telegram_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    confirmation_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_notification_channel_user FOREIGN KEY(user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB;