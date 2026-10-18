-- Phase 4: make tier isolation of secrets a database guarantee.
--
-- EnvironmentCredentialService already refuses to register a secret that is
-- already stored under another environment of the same project. That check is a
-- read followed by a write, so two concurrent requests registering the same
-- production secret into two different sandbox environments can both observe
-- "not present" and both insert. The application check narrows the window; only
-- a constraint closes it.
--
-- The unique key is (project_id, fingerprint):
--   * project_id  - isolation is per project. Two different customers using the
--                   same well-known secret is normal and must not collide.
--   * fingerprint - a keyed digest of the secret, so equality means the same
--                   secret was used. The plaintext is never compared or stored.
--
-- NOT (environment_id, fingerprint): that would only prevent reuse within one
-- environment, which is the case the service already handles by version bump.

-- Existing rows are left alone. If duplicates already exist the index below
-- fails loudly rather than silently deleting a credential, because dropping a
-- stored secret without an audit trail is worse than a failed deploy.
create unique index if not exists environment_credentials_project_fingerprint_unique
    on environment_credentials(project_id, fingerprint);

-- Supports the reuse probe, which filters on project_id and fingerprint while
-- excluding the target environment. Without this the probe degrades to a scan
-- as the credential count grows.
create index if not exists environment_credentials_project_fingerprint_idx
    on environment_credentials(project_id, fingerprint, environment_id);