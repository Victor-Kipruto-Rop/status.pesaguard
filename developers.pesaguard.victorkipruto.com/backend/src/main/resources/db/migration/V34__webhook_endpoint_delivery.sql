create table webhook_endpoints (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    project_id uuid not null references projects(id),
    name varchar(120) not null,
    url varchar(2048) not null,
    signing_secret_ciphertext text not null,
    status varchar(24) not null default 'ACTIVE',
    version_lock bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint webhook_endpoints_status_check check (status in ('ACTIVE', 'SUSPENDED', 'DELETED'))
);

create index webhook_endpoints_tenant_project_idx
    on webhook_endpoints(organization_id, project_id, created_at desc);

create index event_deliveries_subscription_latest_idx
    on event_deliveries(event_id, subscription_id, attempt desc);
