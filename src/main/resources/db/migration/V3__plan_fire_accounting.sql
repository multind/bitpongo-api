CREATE TABLE plan_fire_execution (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plan_id INT NOT NULL,
    scheduled_fire_time DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_plan_fire_execution (plan_id, scheduled_fire_time)
) ENGINE=InnoDB;
