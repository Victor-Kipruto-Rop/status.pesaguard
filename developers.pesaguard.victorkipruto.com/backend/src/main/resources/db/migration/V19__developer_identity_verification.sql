-- Phase 2 follow-up: developer identity completion.
--
-- Two gaps closed: email verification state, and the auth_sessions device columns
-- that V15 created but nothing read or wrote.

-- Email verification.
--
-- Nullable: an unverified account is a legitimate state, because registration
-- must not block on an email round trip that may be filtered. NOT NULL with a
-- sentinel would be a lie.
alter table users
    add column if not exists email_verified_at timestamptz,
    add column if not exists email_verification_hash varchar(64);

-- Unique so the same token cannot be issued to two accounts, and so verification
-- is an index hit rather than a table scan.
create unique index if not exists users_email_verification_idx
    on users(email_verification_hash)
    where email_verification_hash is not null;

-- A verified account must have no outstanding token: a leftover hash would keep
-- the address "re-verifiable" forever.
alter table users
    add constraint users_email_verification_pair_check check (
        email_verified_at is null or email_verification_hash is null
    );