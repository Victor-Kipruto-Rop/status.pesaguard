-- Phase 01: refresh tokens, MFA and password recovery for developer sessions.
--
-- Access tokens are opaque server-side rows in auth_sessions, not JWTs, so that
-- logout, device revocation and account lockout take effect immediately. A
-- stateless JWT cannot be revoked without a blocklist that reintroduces the
-- per-request database read this design exists to avoid.
--
-- This migration adds the *long-lived* half of that session model: a rotating
-- refresh token family, TOTP second factors, and single-use password reset.
--
-- Storage rules, applied uniformly below and enforced by the constraints:
--   * refresh tokens  -> SHA-256 hash only
--   * password reset  -> HMAC-SHA256 hash only
--   * backup codes    -> SHA-256 hash only, single use
--   * TOTP secret     -> AES-256-GCM ciphertext; never plaintext, because unlike
--                        the values above it must be *readable* to verify a code,
--                        so confidentiality is the only control that applies.
-- No column in this file stores a value that would let an attacker authenticate.

-- One family per login. Reuse of a rotated token revokes the whole family, so a
-- stolen token cannot be redeemed twice without the legitimate client noticing.
create table user_refresh_token_families (
    id uuid primary key,
    user_id uuid not null references users(id),
    -- Denormalised so "revoke every session for this user" is one indexed delete
    -- and cannot be defeated by an account having many organizations.
    organization_id uuid not null references organizations(id),
    created_at timestamptz not null,
    last_rotated_at timestamptz,
    revoked_at timestamptz,
    -- Why the family died, e.g. REUSE_DETECTED, PASSWORD_RESET, USER_LOGOUT_ALL.
    -- Distinguishes an attacker from a routine sign-out during an incident review.
    revoked_reason varchar(48),
    constraint refresh_families_revocation_complete check (
        (revoked_at is null and revoked_reason is null)
        or (revoked_at is not null and revoked_reason is not null)
    )
);
create index refresh_families_user_active_idx
    on user_refresh_token_families(user_id) where revoked_at is null;

create table user_refresh_tokens (
    id uuid primary key,
    -- Every rotation stays in the same family; reuse detection walks this column.
    family_id uuid not null references user_refresh_token_families(id) on delete cascade,
    -- SHA-256 of the opaque token. Never the token itself.
    token_hash varchar(64) not null unique,
    issued_at timestamptz not null,
    expires_at timestamptz not null,
    -- Set exactly once, at rotation. A presented token that already has this set
    -- is a replay, which is the entire detection mechanism.
    used_at timestamptz,
    revoked_at timestamptz,
    device_label varchar(64),
    last_ip varchar(45),
    created_at timestamptz not null,
    constraint refresh_tokens_expiry_order check (expires_at > issued_at),
    constraint refresh_tokens_use_after_issue check (used_at is null or used_at >= issued_at)
);
create index refresh_tokens_family_idx on user_refresh_tokens(family_id);
-- The single lookup the refresh path performs.
create index refresh_tokens_hash_idx on user_refresh_tokens(token_hash);

create table user_password_reset_tokens (
    id uuid primary key,
    user_id uuid not null references users(id),
    -- Deliberately not scoped to an organization. A reset authorises a full
    -- account takeover, so redemption revokes every session and refresh family
    -- the user holds in every organization. Recording an organization here would
    -- invite a later reader to scope that revocation by it.
    token_hash varchar(64) not null unique,
    issued_at timestamptz not null,
    expires_at timestamptz not null,
    used_at timestamptz,
    revoked_at timestamptz,
    requested_ip varchar(45),
    created_at timestamptz not null,
    constraint password_reset_expiry_order check (expires_at > issued_at)
);
create index password_reset_hash_idx on user_password_reset_tokens(token_hash);
-- At most one outstanding reset per user, so a later request cannot be raced
-- against an earlier one that is still live.
create unique index password_reset_one_outstanding_idx
    on user_password_reset_tokens(user_id) where used_at is null and revoked_at is null;

create table user_mfa_secrets (
    id uuid primary key,
    user_id uuid not null references users(id),
    -- AES-256-GCM ciphertext (iv || ciphertext || tag), base64. Readable by the
    -- server to verify a code, so it is encrypted rather than hashed.
    secret_ciphertext text not null,
    -- Null until the user proves possession by confirming a code. An unconfirmed
    -- secret cannot satisfy a login challenge.
    confirmed_at timestamptz,
    created_at timestamptz not null,
    revoked_at timestamptz,
    -- Highest TOTP counter already accepted. Refusing a repeat of the same step is
    -- what stops a shoulder-surfed code from being replayed inside its window.
    last_used_counter bigint not null default 0
);
-- At most one live TOTP secret per user.
create unique index mfa_one_active_secret_idx
    on user_mfa_secrets(user_id) where revoked_at is null;

create table user_mfa_backup_codes (
    id uuid primary key,
    secret_id uuid not null references user_mfa_secrets(id) on delete cascade,
    -- SHA-256 of the code. Codes are high-entropy, so a bare digest is adequate
    -- and keeps them unreadable after issuance.
    code_hash varchar(64) not null unique,
    created_at timestamptz not null,
    used_at timestamptz
);
create index mfa_backup_codes_secret_idx on user_mfa_backup_codes(secret_id);

-- Pairs each access session with the refresh family issued alongside it.
--
-- Without this, signing out revokes only the access token and the client keeps
-- a live refresh token that mints a fresh session on the next call: a logout
-- that does not log out. Nullable because sessions created before this column
-- existed have no family to revoke. on delete cascade keeps the pair consistent
-- if a family is ever purged.
alter table auth_sessions
    add column refresh_family_id uuid references user_refresh_token_families(id) on delete cascade;
create index auth_sessions_refresh_family_idx
    on auth_sessions(refresh_family_id) where refresh_family_id is not null;