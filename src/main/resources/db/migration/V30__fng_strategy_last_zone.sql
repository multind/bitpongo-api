ALTER TABLE fng_strategy
    ADD COLUMN last_zone VARCHAR(8) NULL AFTER last_observed_at;