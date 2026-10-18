-- New environment credentials retain authenticated ciphertext for internal
-- runtime integrations while public APIs continue to return metadata only.
-- Existing rows were stored as one-way hashes and cannot be reconstructed.
alter table environment_credentials
    add column if not exists encrypted_secret text;
