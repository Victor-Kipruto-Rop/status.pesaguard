create table api_key_creation_idempotency (
    id uuid primary key,
    organization_id uuid not null,
    user_id uuid not null,
    idempotency_key_hash varchar(64) not null,
    request_fingerprint varchar(64) not null,
    api_key_id uuid not null,
    api_key_name varchar(120) not null,
    key_prefix varchar(32) not null,
    secret_ciphertext text not null,
    scopes text not null,
    credential_expires_at timestamptz,
    expires_at timestamptz not null,
    created_at timestamptz not null default now(),
    constraint api_key_creation_idempotency_scope_key
        unique (organization_id, user_id, idempotency_key_hash)
);

create index api_key_creation_idempotency_expiry_idx
    on api_key_creation_idempotency (expires_at);
