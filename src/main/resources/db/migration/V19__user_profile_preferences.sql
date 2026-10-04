CREATE TABLE user_profile_preferences (
    user_id INT NOT NULL PRIMARY KEY,
    country CHAR(2) NOT NULL DEFAULT '',
    introduction VARCHAR(500) NOT NULL DEFAULT '',
    up_down_color VARCHAR(16) NOT NULL DEFAULT 'GREEN_UP',
    CONSTRAINT fk_profile_preferences_user FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE,
    CONSTRAINT ck_profile_preferences_color CHECK (up_down_color IN ('GREEN_UP', 'RED_UP'))
);
