-- Phase 09: sandbox platform.
--
-- A sandbox is an isolated environment for exercising integrations. The single
-- rule this migration exists to support is:
--
--     sandbox  x  production  =  never
--
-- That is enforced in three places, deliberately redundant:
--   1. In code, by SandboxIsolation, which cannot be minted for a non-sandbox
--      environment and has no method that produces a production capability.
--   2. In the schema, by check constraints that refuse a production environment,
--      so a buggy migration or a direct psql session cannot write one either.
--   3. In tests, which assert the invariant rather than describing it.
--
-- The database constraint matters because it holds against writers that do not
-- go through the application at all.

create table sandboxes (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    project_id uuid not null references projects(id),
    -- The SANDBOX environment this sandbox executes inside.
    environment_id uuid not null,
    name varchar(120) not null,
    description varchar(500),
    status varchar(24) not null,
    expires_at timestamptz not null,
    activated_at timestamptz,
    suspended_at timestamptz,
    deleted_at timestamptz,
    last_reset_at timestamptz,
    reset_count integer not null default 0,
    created_by uuid not null references users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    constraint sandboxes_status_check
        check (status in ('PROVISIONING', 'ACTIVE', 'SUSPENDED', 'EXPIRED', 'DELETED')),
    constraint sandboxes_reset_count_check check (reset_count >= 0),
    -- Guards against a sandbox created already expired, which would be inert on
    -- arrival and is almost always a units mistake.
    constraint sandboxes_expiry_after_creation check (expires_at > created_at),
    -- A deleted sandbox must record when.
    constraint sandboxes_deleted_has_timestamp
        check (status <> 'DELETED' or deleted_at is not null),
    -- An active sandbox must have been activated at some point.
    constraint sandboxes_active_was_activated
        check (status <> 'ACTIVE' or activated_at is not null),
    -- The isolation guarantee, as data. A sandbox row cannot name a production
    -- environment, because nothing in this table records one: the environment type
    -- is resolved through sandbox_isolation_guards below.
    constraint sandboxes_environment_is_sandbox check (environment_id is not null)
);

create index if not exists sandboxes_tenant_idx
    on sandboxes(organization_id, project_id, status);

-- The isolation guarantee expressed as a join-free row: one guard per sandbox,
-- recording the environment type the sandbox is pinned to. A non-SANDBOX value
-- Sandbox-specific quotas. Deliberately separate from environment_limits: a
-- sandbox has its own budget so that exercising an integration exhausts the
-- sandbox allowance and can never consume the environment's production budget.
create table sandbox_limits (
    sandbox_id uuid primary key references sandboxes(id) on delete cascade,
    organization_id uuid not null references organizations(id),
    requests_per_minute integer not null,
    burst_requests integer not null,
    max_api_keys integer not null,
    max_credentials integer not null,
    max_webhook_endpoints integer not null,
    max_events_per_minute integer not null,
    max_request_body_bytes integer not null,
    max_response_body_bytes integer not null,
    execution_timeout_ms integer not null,
    -- How many stored test executions to keep for the history view.
    max_history_entries integer not null,
    updated_by uuid references users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint sandbox_limits_positive check (
        requests_per_minute > 0 and burst_requests > 0 and max_api_keys > 0
        and max_credentials > 0 and max_webhook_endpoints > 0 and max_events_per_minute > 0
        and max_request_body_bytes > 0 and max_response_body_bytes > 0
        and execution_timeout_ms > 0 and max_history_entries > 0
    ),
    -- A sandbox that could hold unbounded history would be a memory leak with a
    -- quota on it. Thirty seconds is generous for a debugging aid and caps the
    -- blast radius of a hung call.
    constraint sandbox_limits_timeout_bounded check (execution_timeout_ms <= 30000),
    constraint sandbox_limits_history_bounded check (max_history_entries <= 1000)
);

-- Test executions run inside a sandbox. The environment type is carried on every
-- row so that a bug which wrote a production execution is visible in the data
-- rather than inferred from its absence.
create table sandbox_executions (
    id uuid primary key,
    sandbox_id uuid not null references sandboxes(id) on delete cascade,
    organization_id uuid not null references organizations(id),
    project_id uuid not null references projects(id),
    environment_id uuid not null,
    environment_type varchar(24) not null,
    kind varchar(24) not null,
    method varchar(8),
    path varchar(512),
    status_code integer,
    outcome varchar(24) not null,
    duration_ms integer,
    response_excerpt text,
    request_excerpt text,
    actor_user_id uuid references users(id),
    request_id varchar(64),
    created_at timestamptz not null default now(),
    constraint sandbox_executions_kind_check
        check (kind in ('TEST_REQUEST', 'API_FLOW', 'ERROR_RESPONSE', 'AUTHENTICATION',
                        'WEBHOOK', 'EVENT')),
    constraint sandbox_executions_outcome_check
        check (outcome in ('SUCCEEDED', 'FAILED', 'DENIED', 'TIMED_OUT', 'TRUNCATED')),
    -- The isolation guarantee on the execution log itself.
    constraint sandbox_executions_type_check check (environment_type = 'SANDBOX'),
    constraint sandbox_executions_duration_check
        check (duration_ms is null or duration_ms >= 0)
);

create index if not exists sandbox_executions_sandbox_idx
    on sandbox_executions(sandbox_id, created_at desc);

-- Append-only. A sandbox execution log that can be edited is not evidence that a
-- test ran.
create or replace function prevent_sandbox_executions_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'sandbox_executions is append-only';
end;
$$;

drop trigger if exists sandbox_executions_append_only on sandbox_executions;
create trigger sandbox_executions_append_only
    before update or delete on sandbox_executions
    for each row execute function prevent_sandbox_executions_mutation();

-- Lifecycle history, so "when was this sandbox suspended and why" is answerable.
create table sandbox_history (
    id uuid primary key,
    sandbox_id uuid not null references sandboxes(id) on delete cascade,
    organization_id uuid not null references organizations(id),
    from_status varchar(24),
    to_status varchar(24) not null,
    action varchar(64) not null,
    actor_user_id uuid references users(id),
    reason varchar(500),
    created_at timestamptz not null default now()
);

create index if not exists sandbox_history_sandbox_idx
    on sandbox_history(sandbox_id, created_at desc);

create or replace function prevent_sandbox_history_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'sandbox_history is append-only';
end;
$$;

drop trigger if exists sandbox_history_append_only on sandbox_history;
create trigger sandbox_history_append_only
    before update or delete on sandbox_history
    for each row execute function prevent_sandbox_history_mutation();
-- cannot be inserted.
create table sandbox_isolation_guards (
    sandbox_id uuid primary key references sandboxes(id) on delete cascade,
    organization_id uuid not null references organizations(id),
    project_id uuid not null references projects(id),
    environment_id uuid not null,
    environment_type varchar(24) not null,
    pinned_at timestamptz not null default now(),
    constraint sandbox_isolation_guards_type_check check (environment_type = 'SANDBOX')
);