alter table api_keys
    add column encrypted_secret text;

create table integrations (
    id uuid primary key,
    organization_id uuid not null,
    project_id uuid not null,
    environment_id uuid not null,
    environment_type varchar(24) not null,
    type varchar(32) not null,
    provider varchar(32) not null,
    status varchar(24) not null,
    health_status varchar(24) not null,
    display_name varchar(120) not null,
    description varchar(500) not null,
    enabled boolean not null default true,
    last_tested_at timestamptz,
    last_success_at timestamptz,
    last_failure_at timestamptz,
    last_request_id uuid,
    last_trace_id varchar(128),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    constraint integrations_environment_fk
        foreign key (environment_id, project_id, organization_id)
        references project_environments(id, project_id, organization_id),
    constraint integrations_provider_type_check
        check (type = 'PESAGUARD_API' and provider = 'PESAGUARD'),
    constraint integrations_status_check
        check (status in ('NOT_CONFIGURED', 'CONFIGURING', 'READY_TO_TEST', 'TESTING',
            'CONNECTED', 'DEGRADED', 'FAILED', 'DISABLED')),
    constraint integrations_health_check
        check (health_status in ('UNKNOWN', 'HEALTHY', 'DEGRADED', 'UNHEALTHY')),
    constraint integrations_environment_unique
        unique (environment_id, type)
);

create index integrations_project_idx on integrations(organization_id, project_id, created_at desc);
create index integrations_environment_idx on integrations(organization_id, environment_id);

create table integration_capabilities (
    id uuid primary key,
    integration_id uuid not null references integrations(id) on delete cascade,
    capability varchar(32) not null,
    status varchar(24) not null,
    required_scopes varchar(500) not null default '',
    enabled boolean not null default false,
    last_verified_at timestamptz,
    created_at timestamptz not null default now(),
    constraint integration_capabilities_type_check check (
        capability in ('PAYMENTS', 'TRANSACTIONS', 'MPESA', 'AIRTEL_MONEY',
            'BANKS', 'RECONCILIATION', 'RISK', 'WEBHOOKS')),
    constraint integration_capabilities_status_check check (
        status in ('AVAILABLE', 'ENABLED', 'DISABLED', 'REQUIRES_SCOPE', 'UNAVAILABLE', 'FAILED')),
    constraint integration_capability_unique unique (integration_id, capability)
);

insert into integrations (
    id, organization_id, project_id, environment_id, environment_type,
    type, provider, status, health_status, display_name, description, enabled, created_at, updated_at, version
)
select
    gen_random_uuid(), project_environment.organization_id, project_environment.project_id,
    project_environment.id, project_environment.type,
    'PESAGUARD_API', 'PESAGUARD', 'NOT_CONFIGURED', 'UNKNOWN', 'PesaGuard API',
    'Environment-scoped connection to the PesaGuard API.', true, now(), now(), 0
from project_environments project_environment
on conflict (environment_id, type) do nothing;

insert into integration_capabilities (id, integration_id, capability, status, required_scopes, enabled)
select gen_random_uuid(), integration.id, capability.capability, 'AVAILABLE', capability.required_scopes, false
from integrations integration
cross join (values
    ('PAYMENTS', 'payments:read,payments:write'),
    ('TRANSACTIONS', 'transactions:read,transactions:write'),
    ('MPESA', 'mpesa:read,mpesa:write'),
    ('AIRTEL_MONEY', 'airtel:read,airtel:write'),
    ('BANKS', 'banks:read,banks:write'),
    ('RECONCILIATION', 'reconciliation:read,reconciliation:write'),
    ('RISK', 'fraud:read'),
    ('WEBHOOKS', 'webhooks:read,webhooks:write')
) as capability(capability, required_scopes)
where integration.type = 'PESAGUARD_API'
on conflict (integration_id, capability) do nothing;

create table integration_test_runs (
    id uuid primary key,
    integration_id uuid not null references integrations(id) on delete cascade,
    test_type varchar(32) not null,
    status varchar(24) not null,
    request_id uuid not null,
    latency_ms bigint,
    failure_category varchar(48),
    safe_message varchar(500) not null,
    started_at timestamptz not null,
    completed_at timestamptz,
    constraint integration_test_type_check check (test_type in ('CONNECTION')),
    constraint integration_test_status_check check (status in ('RUNNING', 'SUCCESS', 'FAILED'))
);

create index integration_test_runs_history_idx
    on integration_test_runs(integration_id, started_at desc);

create table integration_events (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    integration_id uuid not null references integrations(id) on delete cascade,
    actor_user_id uuid references users(id),
    event_type varchar(64) not null,
    request_id uuid not null,
    details varchar(1000) not null default '',
    created_at timestamptz not null default now()
);

create index integration_events_history_idx
    on integration_events(integration_id, created_at desc);
