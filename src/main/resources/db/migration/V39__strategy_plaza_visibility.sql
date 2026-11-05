-- Strategy plaza is opt-in: a strategy is listed only when its owner turns this on
-- (or an administrator marks it official in strategy_mark). Absence of a row means private.
CREATE TABLE strategy_visibility (
    strategy_type VARCHAR(8) NOT NULL,
    strategy_id INT NOT NULL,
    user_id INT NOT NULL,
    is_public BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (strategy_type, strategy_id),
    KEY ix_strategy_visibility_user (user_id)
) ENGINE=InnoDB;
