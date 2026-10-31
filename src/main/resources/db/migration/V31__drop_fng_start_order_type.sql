-- The product keeps only the strategy direction. Opening direction is no longer part of the model.
ALTER TABLE fng_strategy DROP COLUMN start_order_type;