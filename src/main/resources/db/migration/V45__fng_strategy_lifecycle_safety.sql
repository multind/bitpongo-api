ALTER TABLE fng_strategy ADD COLUMN archived_at DATETIME(6) NULL;
ALTER TABLE fng_position
    ADD COLUMN entry_observed_qty DECIMAL(36,18) NOT NULL DEFAULT 0,
    ADD INDEX ix_fng_position_poll (status, updated_at, id);
