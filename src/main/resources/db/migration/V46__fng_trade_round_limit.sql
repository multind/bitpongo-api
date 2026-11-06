ALTER TABLE fng_strategy
    MODIFY COLUMN bureau_period INT NULL,
    ADD COLUMN max_trade_rounds INT NULL,
    ADD COLUMN completed_trade_rounds BIGINT NOT NULL DEFAULT 0;

-- A number of days is not a number of rounds. Keep old positions managed
-- but require the owner to configure a round limit before resuming entries.
UPDATE fng_strategy SET enabled=false WHERE max_trade_rounds IS NULL;
