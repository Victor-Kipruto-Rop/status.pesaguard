create table passkey_credentials (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    credential_id bytea not null unique,
    user_handle bytea not null,
    public_key_cose bytea not null,
    signature_count bigint not null default 0,
    backup_eligible boolean not null default false,
    backed_up boolean not null default false,
    display_name varchar(80) not null,
    created_at timestamptz not null,
    last_used_at timestamptz
);

create index ix_passkey_credentials_user on passkey_credentials(user_id, created_at);

create table passkey_challenges (
    id uuid primary key,
    purpose varchar(32) not null,
    user_id uuid references users(id) on delete cascade,
    organization_id uuid references organizations(id) on delete cascade,
    request_json text not null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    consumed_at timestamptz
);

create index ix_passkey_challenges_expiry on passkey_challenges(expires_at)
    where consumed_at is null;
create index ix_passkey_challenges_cleanup on passkey_challenges(expires_at);

create table passkey_unattributed_authentication_failures (
    id uuid primary key,
    created_at timestamptz not null
);

create function prevent_passkey_failure_mutation() returns trigger
language plpgsql as $$
begin
    raise exception 'passkey authentication failure records are append-only';
end;
$$;

create trigger passkey_failure_append_only
before update or delete on passkey_unattributed_authentication_failures
for each row execute function prevent_passkey_failure_mutation();
