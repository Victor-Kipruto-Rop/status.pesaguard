-- PesaGuard developer platform foundation.
-- All monetary or production-facing records are intentionally out of scope for this migration.

create table users (
    id uuid primary key,
    email varchar(320) not null unique,
    display_name varchar(120) not null,
    password_hash varchar(100) not null,
    status varchar(24) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint users_email_lowercase_check check (email = lower(email)),
    constraint users_status_check check (status in ('ACTIVE', 'SUSPENDED', 'DEACTIVATED'))
);

create table organizations (
    id uuid primary key,
    name varchar(120) not null,
    slug varchar(80) not null unique,
    status varchar(24) not null,
    owner_user_id uuid not null references users(id),
    audit_sequence bigint not null default 0,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint organizations_status_check check (status in ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

create table organization_memberships (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    user_id uuid not null references users(id),
    role varchar(24) not null,
    status varchar(24) not null,
    created_at timestamptz not null,
    constraint organization_memberships_unique unique (organization_id, user_id),
    constraint organization_memberships_role_check check (role in ('OWNER', 'ADMIN', 'DEVELOPER', 'VIEWER')),
    constraint organization_memberships_status_check check (status in ('ACTIVE', 'SUSPENDED', 'REVOKED'))
);
create index organization_memberships_user_idx on organization_memberships(user_id, status);

create table auth_sessions (
    id uuid primary key,
    token_hash varchar(64) not null unique,
    membership_id uuid not null references organization_memberships(id),
    expires_at timestamptz not null,
    revoked_at timestamptz,
    last_seen_at timestamptz not null,
    created_at timestamptz not null
);
create index auth_sessions_active_idx on auth_sessions(token_hash, expires_at) where revoked_at is null;

create table login_throttles (
    subject_type varchar(16) not null,
    subject_hash varchar(64) not null,
    failure_count integer not null,
    window_started_at timestamptz not null,
    blocked_until timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (subject_type, subject_hash),
    constraint login_throttles_type_check check (subject_type in ('account', 'ip')),
    constraint login_throttles_failure_count_check check (failure_count >= 0)
);

create table projects (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    name varchar(120) not null,
    slug varchar(80) not null,
    status varchar(24) not null,
    created_by uuid not null references users(id),
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint projects_slug_unique unique (organization_id, slug),
    constraint projects_id_organization_unique unique (id, organization_id),
    constraint projects_status_check check (status in ('ACTIVE', 'ARCHIVED'))
);
create index projects_organization_idx on projects(organization_id, created_at desc);

create table project_environments (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    project_id uuid not null references projects(id),
    name varchar(80) not null,
    type varchar(24) not null,
    status varchar(24) not null,
    created_by uuid not null references users(id),
    created_at timestamptz not null,
    constraint project_environments_name_unique unique (project_id, name),
    constraint project_environments_id_project_organization_unique unique (id, project_id, organization_id),
    constraint project_environments_type_check check (type = 'SANDBOX'),
    constraint project_environments_status_check check (status in ('ACTIVE', 'SUSPENDED')),
    constraint project_environments_project_org_fk foreign key (project_id, organization_id)
        references projects(id, organization_id)
);
create index project_environments_project_idx on project_environments(project_id, created_at);

create table api_keys (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    project_id uuid not null references projects(id),
    environment_id uuid not null references project_environments(id),
    name varchar(120) not null,
    key_prefix varchar(32) not null unique,
    secret_hash varchar(64) not null unique,
    scopes text not null,
    status varchar(24) not null,
    expires_at timestamptz,
    revoked_at timestamptz,
    last_used_at timestamptz,
    created_by uuid not null references users(id),
    version bigint not null default 0,
    created_at timestamptz not null,
    constraint api_keys_status_check check (status in ('ACTIVE', 'REVOKED', 'EXPIRED')),
    constraint api_keys_environment_project_fk foreign key (environment_id, project_id, organization_id)
        references project_environments(id, project_id, organization_id)
);
create index api_keys_environment_idx on api_keys(environment_id, status, created_at desc);

create table audit_events (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    sequence_number bigint not null,
    actor_user_id uuid not null references users(id),
    action varchar(100) not null,
    resource_type varchar(80) not null,
    resource_id varchar(100) not null,
    request_id uuid not null,
    metadata text not null,
    previous_hash varchar(64) not null,
    event_hash varchar(64) not null unique,
    created_at timestamptz not null,
    constraint audit_events_sequence_unique unique (organization_id, sequence_number)
);
create index audit_events_organization_idx on audit_events(organization_id, sequence_number desc);

create or replace function prevent_audit_event_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'audit_events is append-only';
end;
$$;

create trigger audit_events_append_only
before update or delete on audit_events
for each row execute function prevent_audit_event_mutation();
