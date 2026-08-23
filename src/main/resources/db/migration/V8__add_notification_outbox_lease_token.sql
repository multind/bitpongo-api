ALTER TABLE notification_outbox
    ADD COLUMN lease_token VARCHAR(36) NULL AFTER lease_until;
