CREATE TABLE outbound_webhook_preference (
    user_id INT NOT NULL PRIMARY KEY,
    enabled BOOLEAN NOT NULL,
    CONSTRAINT fk_outbound_webhook_preference_user FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
);
