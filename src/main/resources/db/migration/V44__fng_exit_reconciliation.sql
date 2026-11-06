ALTER TABLE fng_position
    ADD COLUMN exit_filled_qty DECIMAL(36,18) NOT NULL DEFAULT 0,
    ADD COLUMN exit_filled_quote DECIMAL(65,30) NOT NULL DEFAULT 0,
    ADD COLUMN exit_order_filled_qty DECIMAL(36,18) NOT NULL DEFAULT 0,
    ADD COLUMN exit_order_filled_quote DECIMAL(65,30) NOT NULL DEFAULT 0;
