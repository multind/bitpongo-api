CREATE TABLE user_avatar (
    user_id INT NOT NULL PRIMARY KEY,
    image MEDIUMBLOB NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_avatar_user FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
);
