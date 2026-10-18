create table load_tests (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    project_id uuid not null references projects(id),
    environment_id uuid not null references project_environments(id),
    created_by uuid not null references users(id),
    name varchar(120) not null,
    description varchar(1000),
    endpoint_path varchar(1024) not null,
    http_method varchar(10) not null,
    content_type varchar(100) not null,
    headers jsonb not null default '{}'::jsonb,
    request_body text,
    api_key_id uuid references api_keys(id) on delete set null,
    load_pattern varchar(20) not null,
    target_vus integer not null,
    target_rps integer,
    maximum_rps integer,
    maximum_duration_seconds integer not null,
    stages jsonb not null,
    thresholds jsonb not null,
    lifecycle varchar(24) not null,
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint load_tests_vus_positive check (target_vus > 0 and target_vus <= 100000),
    constraint load_tests_duration_positive check (maximum_duration_seconds > 0),
    constraint load_tests_rps_positive check (
        (target_rps is null or target_rps > 0)
        and (maximum_rps is null or maximum_rps > 0)
        and (target_rps is null or maximum_rps is null or target_rps <= maximum_rps)
    )
);
create index load_tests_tenant_project_idx on load_tests(organization_id, project_id, environment_id, created_at desc);

create table load_test_runs (
    id uuid primary key,
    load_test_id uuid not null references load_tests(id),
    organization_id uuid not null references organizations(id),
    project_id uuid not null references projects(id),
    environment_id uuid not null references project_environments(id),
    created_by uuid not null references users(id),
    status varchar(24) not null,
    availability_reason varchar(100),
    requested_vus integer not null,
    allocated_vus integer not null default 0,
    requested_rps integer,
    estimated_duration_seconds bigint,
    started_at timestamptz,
    finished_at timestamptz,
    configuration_snapshot jsonb not null,
    result_summary jsonb,
    failure_reason varchar(500),
    version bigint not null default 0,
    created_at timestamptz not null default now()
);
create index load_test_runs_tenant_idx on load_test_runs(organization_id, project_id, environment_id, created_at desc);
create index load_test_runs_queue_idx on load_test_runs(status, created_at) where status = 'QUEUED';

create table load_generators (
    id uuid primary key,
    name varchar(120) not null,
    region varchar(80),
    max_vus integer not null,
    max_rps integer not null,
    current_vus integer not null default 0,
    current_rps integer not null default 0,
    cpu_percent double precision not null default 0,
    memory_percent double precision not null default 0,
    version varchar(40) not null,
    executor_type varchar(20) not null,
    status varchar(24) not null,
    last_heartbeat_at timestamptz,
    version_number bigint not null default 0,
    updated_at timestamptz not null default now(),
    constraint load_generators_capacity_positive check (max_vus > 0 and max_rps >= 0),
    constraint load_generators_usage_nonnegative check (current_vus >= 0 and current_rps >= 0)
);
create index load_generators_available_idx on load_generators(status, max_vus, max_rps);

create table load_generator_heartbeats (
    id uuid primary key,
    generator_id uuid not null references load_generators(id) on delete cascade,
    status varchar(24) not null,
    current_vus integer not null,
    current_rps integer not null,
    cpu_percent double precision not null,
    memory_percent double precision not null,
    observed_at timestamptz not null
);
create index load_generator_heartbeats_recent_idx on load_generator_heartbeats(generator_id, observed_at desc);

create table load_test_generator_allocations (
    id uuid primary key,
    run_id uuid not null references load_test_runs(id) on delete cascade,
    generator_id uuid not null references load_generators(id),
    allocated_vus integer not null,
    allocated_rps integer not null default 0,
    status varchar(24) not null,
    claimed_at timestamptz,
    constraint load_test_allocation_per_generator unique(run_id, generator_id)
);
create index load_test_allocations_claim_idx on load_test_generator_allocations(generator_id, status);

create table load_test_metrics (
    id uuid primary key,
    run_id uuid not null references load_test_runs(id) on delete cascade,
    generator_id uuid not null references load_generators(id),
    observed_at timestamptz not null,
    values jsonb not null
);
create index load_test_metrics_run_time_idx on load_test_metrics(run_id, observed_at);

create table load_test_results (
    id uuid primary key,
    run_id uuid not null unique references load_test_runs(id) on delete cascade,
    summary jsonb not null,
    verdict varchar(16) not null,
    recorded_at timestamptz not null
);

create table load_test_events (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    project_id uuid not null references projects(id),
    environment_id uuid not null references project_environments(id),
    load_test_id uuid references load_tests(id) on delete set null,
    run_id uuid references load_test_runs(id) on delete set null,
    actor_id uuid references users(id) on delete set null,
    event_type varchar(80) not null,
    payload jsonb not null,
    occurred_at timestamptz not null
);
create index load_test_events_scope_idx on load_test_events(organization_id, project_id, occurred_at desc);
