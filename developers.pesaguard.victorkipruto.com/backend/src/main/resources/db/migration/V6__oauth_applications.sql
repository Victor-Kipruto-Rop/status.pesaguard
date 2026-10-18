-- Phase 06: OAuth applications and authorization server core.
--
-- Security posture, stated up front because it is the whole point of this module:
--   * client secrets and authorization codes are stored as HMAC hashes only;
--   * authorization codes are single-use and expire in minutes;
--   * PKCE S256 is mandatory - the "plain" method is not supported;
--   * refresh tokens rotate on every use and are grouped into a family, so
--     presenting an already-used token revokes the whole family;
--   * redirect URIs are compared by exact match, never by prefix or wildcard.
--
-- Consent UI, discovery documents, and JWKS signing are NOT implemented here and
-- are listed as open work rather than stubbed.

create table oauth_applications (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    project_id uuid references projects(id),
    environment_id uuid references project_environments(id),
    name varchar(120) not null,
    description varchar(500),
    client_id varchar(64) not null unique,
    client_secret_hash varchar(64) not null,
    client_secret_hint varchar(12) not null,
    client_secret_version integer not null default 1,
    redirect_uris text not null,
    allowed_origins text not null default '',
    scopes text not null default '',
    grant_types text not null default 'authorization_code,refresh_token',
    status varchar(24) not null,
    verified_at timestamptz,
    verification_reference varchar(120),
    status_changed_at timestamptz not null default now(),
    created_by uuid not null references users(id),
    revoked_at timestamptz,
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint oauth_applications_status_check
        check (status in ('REGISTERED', 'ACTIVE', 'SUSPENDED', 'REVOKED'))
);

create index if not exists oauth_applications_organization_idx
    on oauth_applications(organization_id, status, created_at desc);

create table oauth_authorization_codes (
    id uuid primary key,
    application_id uuid not null references oauth_applications(id),
    organization_id uuid not null references organizations(id),
    user_id uuid not null references users(id),
    code_hash varchar(64) not null unique,
    redirect_uri text not null,
    scopes text not null,
    code_challenge varchar(128) not null,
    code_challenge_method varchar(8) not null default 'S256',
    state_hash varchar(64),
    expires_at timestamptz not null,
    consumed_at timestamptz,
    created_at timestamptz not null default now(),
    constraint oauth_authorization_codes_method_check check (code_challenge_method = 'S256')
);

-- Access and refresh tokens. Refresh tokens share a family so that reuse of a
-- spent token can revoke the whole grant.
create table oauth_refresh_tokens (
    id uuid primary key,
    application_id uuid not null references oauth_applications(id),
    organization_id uuid not null references organizations(id),
    user_id uuid not null references users(id),
    family_id uuid not null,
    token_hash varchar(64) not null unique,
    scopes text not null,
    expires_at timestamptz not null,
    used_at timestamptz,
    revoked_at timestamptz,
    revoke_reason varchar(64),
    replaced_by_id uuid references oauth_refresh_tokens(id),
    created_at timestamptz not null default now()
);

create index if not exists oauth_refresh_tokens_family_idx on oauth_refresh_tokens(family_id);
create index if not exists oauth_refresh_tokens_application_idx
    on oauth_refresh_tokens(application_id, created_at desc);

create table oauth_access_tokens (
    id uuid primary key,
    application_id uuid not null references oauth_applications(id),
    organization_id uuid not null references organizations(id),
    user_id uuid not null references users(id),
    family_id uuid,
    token_hash varchar(64) not null unique,
    scopes text not null,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    revoke_reason varchar(64),
    created_at timestamptz not null default now()
);

create index if not exists oauth_access_tokens_application_idx
    on oauth_access_tokens(application_id, created_at desc);

-- Consent gate. An authorization request is created PENDING and nothing is issued
-- until the resource owner approves it. There is no implicit-grant path.
create table oauth_consent_requests (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    application_id uuid not null references oauth_applications(id),
    user_id uuid not null references users(id),
    redirect_uri text not null,
    scopes text not null,
    -- state_hash is the integrity anchor; state_plaintext is retained only so the
    -- server can echo the client's CSRF token back on the approval redirect.
    -- It is never a credential and is never used to authenticate anything.
    state_hash varchar(64),
    state_plaintext varchar(512),
    code_challenge varchar(128) not null,
    origin varchar(255),
    status varchar(24) not null,
    expires_at timestamptz not null,
    decided_at timestamptz,
    decided_by uuid references users(id),
    created_at timestamptz not null default now(),
    constraint oauth_consent_requests_status_check
        check (status in ('PENDING', 'APPROVED', 'DENIED', 'EXPIRED'))
);

create index if not exists oauth_consent_requests_user_idx
    on oauth_consent_requests(user_id, status, created_at desc);

-- Every token issue/rotate/revoke is recorded so a token grant can be
-- reconstructed after the fact.
create table oauth_token_events (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    application_id uuid not null references oauth_applications(id),
    family_id uuid,
    action varchar(64) not null,
    actor_user_id uuid references users(id),
    reason varchar(500),
    created_at timestamptz not null default now()
);

create index if not exists oauth_token_events_application_idx
    on oauth_token_events(application_id, created_at desc);

create or replace function prevent_oauth_token_events_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'oauth_token_events is append-only';
end;
$$;

create trigger oauth_token_events_no_mutation
    before update or delete on oauth_token_events
    for each row execute function prevent_oauth_token_events_mutation();
create index if not exists oauth_authorization_codes_application_idx
    on oauth_authorization_codes(application_id, created_at desc);