ALTER TABLE user_notification_channel
    CHANGE COLUMN sms_enabled message_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    CHANGE COLUMN phone_enabled call_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    CHANGE COLUMN dingtalk_enabled dingding_enabled BOOLEAN NOT NULL DEFAULT FALSE;