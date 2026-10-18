create table service_accounts (
    id uuid primary key,
    organization_id uuid not null references organizations(id) on delete cascade,
    name varchar(120) not null,
    description varchar(500),
    client_id varchar(64) not null unique,
    client_secret_hash varchar(64) not null,
    client_secret_hint varchar(16) not null,
    scopes text not null,
    status varchar(16) not null,
    created_by uuid not null references users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    revoked_at timestamptz,
    version bigint not null default 0,
    constraint service_accounts_status_check check (status in ('ACTIVE', 'SUSPENDED', 'REVOKED'))
);

create index service_accounts_organization_idx
    on service_accounts(organization_id, status, created_at desc);

create table service_account_access_tokens (
    id uuid primary key,
    service_account_id uuid not null references service_accounts(id) on delete cascade,
    token_hash varchar(64) not null unique,
    scopes text not null,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    created_at timestamptz not null default now()
);

create index service_account_access_tokens_account_idx
    on service_account_access_tokens(service_account_id, expires_at desc);
