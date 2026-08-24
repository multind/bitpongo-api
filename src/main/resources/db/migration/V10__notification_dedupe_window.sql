CREATE TABLE notification_dedupe_window (
    scope_key VARCHAR(191) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (scope_key)
) ENGINE=InnoDB;
