CREATE TABLE strategy_mark (
    strategy_type VARCHAR(8) NOT NULL,
    strategy_id INT NOT NULL,
    official BOOLEAN NOT NULL DEFAULT FALSE,
    recommended BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY(strategy_type, strategy_id)
) ENGINE=InnoDB;

DELETE FROM preset_favorite WHERE preset_key='fng:official';
