-- Exchange secrets are stored as AES-GCM envelopes ("v1:" + Base64), which exceed 64 characters.
ALTER TABLE exchange MODIFY secret_key VARCHAR(512), MODIFY password VARCHAR(512);
