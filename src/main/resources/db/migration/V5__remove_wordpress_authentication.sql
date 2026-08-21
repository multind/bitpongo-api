DROP TABLE IF EXISTS deleted_external_identity;
ALTER TABLE `user` DROP COLUMN auth_provider;
