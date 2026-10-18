create table golive_verifications (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    project_id uuid not null references projects(id),
    environment_id uuid not null references project_environments(id),
    initiated_by uuid not null references users(id),
    idempotency_key varchar(128),
    state varchar(24) not null,
    readiness_percent integer not null,
    passed_checks integer not null,
    failed_checks integer not null,
    warning_checks integer not null,
    checks jsonb not null,
    verified_at timestamptz not null,
    created_at timestamptz not null default now()
);

create unique index golive_verifications_idempotency_unique
    on golive_verifications(organization_id, project_id, environment_id, idempotency_key);

create index golive_verifications_scope_idx
    on golive_verifications(organization_id, project_id, environment_id, verified_at desc);

create table golive_launches (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    project_id uuid not null references projects(id),
    environment_id uuid not null references project_environments(id),
    initiated_by uuid not null references users(id),
    verification_id uuid not null references golive_verifications(id),
    idempotency_key varchar(128) not null,
    status varchar(24) not null,
    failure_reason varchar(500),
    started_at timestamptz not null,
    completed_at timestamptz not null,
    request_id varchar(80),
    created_at timestamptz not null default now(),
    constraint golive_launches_idempotency_unique
        unique (organization_id, project_id, environment_id, idempotency_key)
);

create index golive_launches_scope_idx
    on golive_launches(organization_id, project_id, environment_id, started_at desc);
