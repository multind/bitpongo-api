CREATE TABLE user_notification_channel_order (
    user_id INT NOT NULL,
    channel_code VARCHAR(16) NOT NULL,
    position INT NOT NULL,
    PRIMARY KEY (user_id, channel_code),
    CONSTRAINT fk_channel_order_user FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB;
