create table golive_verification_jobs (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    project_id uuid not null references projects(id),
    environment_id uuid not null references project_environments(id),
    initiated_by uuid not null references users(id),
    idempotency_key varchar(128) not null,
    status varchar(24) not null,
    verification_id uuid references golive_verifications(id),
    failure_reason varchar(500),
    queued_at timestamptz not null,
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz not null default now(),
    constraint golive_verification_jobs_status_check
        check (status in ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    constraint golive_verification_jobs_completion_check
        check ((status in ('QUEUED', 'RUNNING') and completed_at is null)
            or (status in ('COMPLETED', 'FAILED') and completed_at is not null)),
    constraint golive_verification_jobs_result_check
        check ((status = 'COMPLETED' and verification_id is not null)
            or status <> 'COMPLETED'),
    constraint golive_verification_jobs_scope_fk
        foreign key (environment_id, project_id, organization_id)
        references project_environments(id, project_id, organization_id),
    constraint golive_verification_jobs_idempotency_unique
        unique (organization_id, project_id, environment_id, idempotency_key)
);

create index golive_verification_jobs_queue_idx
    on golive_verification_jobs(status, queued_at asc)
    where status = 'QUEUED';

create index golive_verification_jobs_scope_idx
    on golive_verification_jobs(organization_id, project_id, environment_id, queued_at desc);
