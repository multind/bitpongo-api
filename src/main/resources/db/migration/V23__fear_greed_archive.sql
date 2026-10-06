CREATE TABLE fear_greed_history (
    observed_at DATETIME(6) NOT NULL PRIMARY KEY,
    value SMALLINT NOT NULL,
    classification VARCHAR(32) NOT NULL,
    fetched_at DATETIME(6) NOT NULL,
    CONSTRAINT ck_fear_greed_value CHECK (value BETWEEN 0 AND 100)
);
