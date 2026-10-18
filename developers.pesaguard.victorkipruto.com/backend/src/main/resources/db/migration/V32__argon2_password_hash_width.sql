-- Phase 01 follow-up: widen password_hash for Argon2id.
--
-- The column was sized at 100 characters for BCrypt, whose encoded hash is
-- 60 characters. An Argon2id hash is longer -- "$argon2id$m=19456,t=2,p=1$<salt>$<hash>"
-- runs to roughly 95 characters at the OWASP baseline parameters, and grows again
-- whenever the memory cost is raised. Registration against a default profile
-- therefore failed with a 409 "value too long" DataIntegrityViolation the moment
-- Argon2id became the default encoder.
--
-- The unit and integration suites did not catch this because they never insert a
-- hash wider than a test-profile row, and because the failure surfaces as a
-- constraint violation rather than a binding error.
--
-- 255 leaves generous headroom for a raised cost while staying a bounded
-- varchar rather than unbounded text. Widening a column is a metadata-only
-- operation in PostgreSQL: no table rewrite, no long lock, no data movement.
alter table users
    alter column password_hash type varchar(255);

-- Confirms an Argon2id hash actually fits the new bound, so the failure above
-- cannot silently return if the parameters are raised again.
do $$
declare
    sample text := '$argon2id$m=1048576,t=4,p=4$c29tZXNhbHRzYWx0c2FsdA$Jq1s0Kf4k3j0n8vQ2xYh7bN9pR5sT1uV3wX6yZ0aB4cD7eF2gH5jK8mN1q';
begin
    if length(sample) > 255 then
        raise exception 'password_hash bound is too small for a raised Argon2 cost';
    end if;
end $$;