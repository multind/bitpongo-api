ALTER TABLE `user`
    ADD COLUMN auth_provider VARCHAR(16) NOT NULL DEFAULT 'local' AFTER password,
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'active' AFTER auth_provider,
    ADD COLUMN deleted_at DATETIME NULL AFTER last_login;

CREATE TABLE deleted_external_identity (
    id BIGINT NOT NULL AUTO_INCREMENT,
    provider VARCHAR(32) NOT NULL,
    subject VARCHAR(128) NOT NULL,
    deleted_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_deleted_external_identity_provider_subject (provider, subject)
) ENGINE=InnoDB;
