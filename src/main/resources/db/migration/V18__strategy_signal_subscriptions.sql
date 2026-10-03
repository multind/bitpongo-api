CREATE TABLE strategy_subscription (
    user_id INT NOT NULL PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    fear_threshold INT NOT NULL,
    greed_threshold INT NOT NULL,
    valid_from DATETIME(6) NOT NULL,
    last_observed_at DATETIME(6) NULL,
    CONSTRAINT fk_strategy_subscription_user FOREIGN KEY(user_id) REFERENCES user(id) ON DELETE CASCADE,
    CONSTRAINT ck_strategy_subscription_thresholds CHECK(fear_threshold >= 0 AND greed_threshold <= 100 AND fear_threshold < greed_threshold)
);
