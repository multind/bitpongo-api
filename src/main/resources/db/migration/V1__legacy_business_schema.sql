CREATE TABLE `user` (
    id INT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100),
    email VARCHAR(100),
    password VARCHAR(255),
    created_at DATETIME,
    last_login DATETIME,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_email (email),
    KEY ix_user_name (name)
) ENGINE=InnoDB;

CREATE TABLE exchange (
    id INT NOT NULL AUTO_INCREMENT,
    name VARCHAR(64), exchange VARCHAR(64), access_key VARCHAR(64),
    secret_key VARCHAR(64), password VARCHAR(64), status VARCHAR(32),
    user_id INT, created_at DATETIME,
    PRIMARY KEY (id), KEY ix_exchange_user (user_id)
) ENGINE=InnoDB;

CREATE TABLE strategy (
    id INT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100), instalment INT, exchange_id INT,
    frequency VARCHAR(100), cron VARCHAR(100), `condition` VARCHAR(32) NOT NULL,
    user_id INT, created_at DATETIME,
    PRIMARY KEY (id), KEY ix_strategy_user (user_id)
) ENGINE=InnoDB;

CREATE TABLE plan (
    id INT NOT NULL AUTO_INCREMENT,
    total_funds FLOAT, total_revenue FLOAT, total_ratio FLOAT,
    next_time DATETIME, status VARCHAR(32), user_id INT,
    triggered_count INT, created_at DATETIME,
    strategy_id INT NOT NULL, exchange_id INT NOT NULL,
    PRIMARY KEY (id), KEY ix_plan_user (user_id),
    CONSTRAINT fk_plan_strategy FOREIGN KEY (strategy_id) REFERENCES strategy (id),
    CONSTRAINT fk_plan_exchange FOREIGN KEY (exchange_id) REFERENCES exchange (id)
) ENGINE=InnoDB;

CREATE TABLE coin (
    id INT NOT NULL AUTO_INCREMENT,
    proportion VARCHAR(100) NOT NULL, icon VARCHAR(256) NOT NULL,
    min FLOAT NOT NULL, max FLOAT NOT NULL, average_down BOOLEAN NOT NULL,
    symbol VARCHAR(100) NOT NULL, average FLOAT NOT NULL,
    total_amount FLOAT NOT NULL, income FLOAT NOT NULL,
    user_id INT, created_at DATETIME, plan_id INT,
    PRIMARY KEY (id), KEY ix_coin_plan (plan_id),
    CONSTRAINT fk_coin_plan FOREIGN KEY (plan_id) REFERENCES plan (id)
) ENGINE=InnoDB;

CREATE TABLE `order` (
    id INT NOT NULL AUTO_INCREMENT,
    symbol VARCHAR(32) NOT NULL, order_no VARCHAR(64) NOT NULL,
    total_amount FLOAT NOT NULL, average_price FLOAT NOT NULL,
    total_cost FLOAT NOT NULL, fee FLOAT NOT NULL,
    user_id INT, created_at DATETIME, plan_id INT,
    PRIMARY KEY (id), KEY ix_order_plan (plan_id),
    CONSTRAINT fk_order_plan FOREIGN KEY (plan_id) REFERENCES plan (id)
) ENGINE=InnoDB;

CREATE TABLE snapshot (
    id INT NOT NULL AUTO_INCREMENT,
    value VARCHAR(32), type VARCHAR(32), user_id INT,
    created_at DATETIME, plan_id INT,
    PRIMARY KEY (id), KEY ix_snapshot_plan (plan_id),
    CONSTRAINT fk_snapshot_plan FOREIGN KEY (plan_id) REFERENCES plan (id)
) ENGINE=InnoDB;

CREATE TABLE dict (
    id INT NOT NULL AUTO_INCREMENT,
    type VARCHAR(32), sub_type VARCHAR(32), code VARCHAR(32), value VARCHAR(512),
    description VARCHAR(256), parent_code VARCHAR(32), enabled INT, sequence INT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB;
