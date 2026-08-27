ALTER TABLE strategy
    ADD COLUMN schedule_timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai' AFTER cron;

ALTER TABLE `user`
    ADD COLUMN display_timezone_mode VARCHAR(16) NOT NULL DEFAULT 'FOLLOW_DEVICE',
    ADD COLUMN display_timezone VARCHAR(64) NULL,
    ADD COLUMN last_device_timezone VARCHAR(64) NULL;
