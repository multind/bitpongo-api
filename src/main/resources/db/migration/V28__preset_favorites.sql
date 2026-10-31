CREATE TABLE preset_favorite (
    user_id INT NOT NULL,
    preset_key VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, preset_key),
    KEY ix_preset_favorite_key (preset_key),
    CONSTRAINT fk_preset_favorite_user FOREIGN KEY(user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB;