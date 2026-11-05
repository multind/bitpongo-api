-- Stop loss for F&G futures positions as a percentage of position margin (same basis as
-- profit_target_percent). NULL keeps the old behaviour for strategies created before this change.
ALTER TABLE fng_strategy ADD COLUMN stop_loss_percent DECIMAL(36,18) NULL AFTER profit_drawdown_percent;
