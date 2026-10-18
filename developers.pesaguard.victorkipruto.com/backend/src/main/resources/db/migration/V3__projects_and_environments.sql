-- Phase 03: projects and environments.
--
-- Existing ACTIVE projects remain valid and are backfilled with owner and metadata.
--
-- MIGRATION HAZARD (reviewed manually before running):
--   This file adds a unique index on (project_id, type). If any existing project
--   already has more than one environment of the same type - only SANDBOX was
--   permitted before - the index creation will FAIL. That is deliberate: the
--   application never deletes or renames tenant data to make a migration pass.
--   Remediate by explicitly merging or renaming the duplicate environments first.

alter table projects add column if not exists metadata jsonb;
update projects set metadata = '{}'::jsonb where metadata is null;
alter table projects alter column metadata set default '{}'::jsonb;
alter table projects alter column metadata set not null;

alter table projects add column if not exists description varchar(500);

alter table projects add column if not exists owner_user_id uuid;
update projects set owner_user_id = created_by where owner_user_id is null;
alter table projects alter column owner_user_id set not null;
alter table projects drop constraint if exists projects_owner_fk;
alter table projects add constraint projects_owner_fk foreign key (owner_user_id) references users(id);

alter table projects add column if not exists status_changed_at timestamptz;
update projects set status_changed_at = updated_at where status_changed_at is null;
alter table projects alter column status_changed_at set not null;

alter table projects drop constraint if exists projects_status_check;
alter table projects add constraint projects_status_check
    check (status in ('ACTIVE', 'DEACTIVATED', 'ARCHIVED'));

create index if not exists projects_organization_status_idx
    on projects(organization_id, status, created_at desc);

create table project_settings (
    project_id uuid primary key,
    organization_id uuid not null references organizations(id),
    settings jsonb not null default '{}'::jsonb,
    version bigint not null default 0,
    updated_by uuid references users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint project_settings_project_org_fk
        foreign key (project_id, organization_id) references projects(id, organization_id)
);

insert into project_settings (project_id, organization_id, settings)
    select id, organization_id, '{}'::jsonb from projects
    on conflict (project_id) do nothing;

create table project_members (
    id uuid primary key,
    project_id uuid not null,
    organization_id uuid not null references organizations(id),
    user_id uuid not null references users(id),
    role varchar(24) not null,
    status varchar(24) not null,
    added_by uuid not null references users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint project_members_unique unique (project_id, user_id),
    constraint project_members_role_check check (role in ('MANAGER', 'DEVELOPER', 'VIEWER')),
    constraint project_members_status_check check (status in ('ACTIVE', 'REVOKED')),
    constraint project_members_project_org_fk
        foreign key (project_id, organization_id) references projects(id, organization_id)
);
-- Environment tiers. A project may hold at most one environment of each type so
-- that DEVELOPMENT, SANDBOX, STAGING and PRODUCTION stay distinct.
alter table project_environments drop constraint if exists project_environments_type_check;
alter table project_environments add constraint project_environments_type_check
    check (type in ('DEVELOPMENT', 'SANDBOX', 'STAGING', 'PRODUCTION'));
alter table project_environments drop constraint if exists project_environments_status_check;
alter table project_environments add constraint project_environments_status_check
    check (status in ('ACTIVE', 'SUSPENDED', 'DEACTIVATED'));

create unique index project_environments_project_type_unique
    on project_environments(project_id, type);

alter table project_environments add column if not exists configuration jsonb;
update project_environments set configuration = '{}'::jsonb where configuration is null;
alter table project_environments alter column configuration set default '{}'::jsonb;
alter table project_environments alter column configuration set not null;

alter table project_environments add column if not exists updated_at timestamptz;
update project_environments set updated_at = created_at where updated_at is null;
alter table project_environments alter column updated_at set not null;

alter table project_environments add column if not exists status_changed_at timestamptz;
update project_environments set status_changed_at = created_at where status_changed_at is null;
alter table project_environments alter column status_changed_at set not null;

alter table project_environments add column if not exists version bigint not null default 0;

-- Environment credentials are stored as hashes only. The platform cannot return
-- a secret value later, by design: no consumer exists yet and adding reversible
-- storage without a provisioned encryption key would be a security downgrade.
create table environment_credentials (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    project_id uuid not null,
    environment_id uuid not null,
    name varchar(120) not null,
    credential_type varchar(32) not null,
    secret_hash varchar(64) not null,
    fingerprint varchar(64) not null,
    version integer not null default 1,
    status varchar(24) not null,
    rotated_at timestamptz,
    created_by uuid not null references users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint environment_credentials_status_check check (status in ('ACTIVE', 'REVOKED')),
    constraint environment_credentials_name_version_unique unique (environment_id, name, version),
    constraint environment_credentials_env_project_org_fk
        foreign key (environment_id, project_id, organization_id)
        references project_environments(id, project_id, organization_id)
);

create index if not exists environment_credentials_env_idx
    on environment_credentials(environment_id, status);

create table environment_access_policies (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    project_id uuid not null,
    environment_id uuid not null,
    subject_type varchar(24) not null,
    subject_role varchar(24) not null,
    permissions text not null,
    ip_allowlist text,
    created_by uuid not null references users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint environment_access_policies_subject_type_check
        check (subject_type in ('ORGANIZATION', 'PROJECT_MEMBER')),
    constraint environment_access_policies_subject_role_check
        check (subject_role in ('OWNER', 'ADMIN', 'MANAGER', 'DEVELOPER', 'VIEWER')),
    constraint environment_access_policies_env_project_org_fk
        foreign key (environment_id, project_id, organization_id)
        references project_environments(id, project_id, organization_id)
);

create index if not exists environment_access_policies_env_idx
    on environment_access_policies(environment_id, subject_type, subject_role);

create table environment_limits (
    environment_id uuid primary key,
    organization_id uuid not null references organizations(id),
    project_id uuid not null,
    requests_per_minute integer not null default 600,
    burst_requests integer not null default 100,
    max_api_keys integer not null default 5,
    max_credentials integer not null default 20,
    credential_rotation_interval_minutes integer not null default 60,
    updated_by uuid references users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint environment_limits_positive_check check (
        requests_per_minute > 0 and burst_requests > 0 and max_api_keys > 0
        and max_credentials > 0 and credential_rotation_interval_minutes > 0),
    constraint environment_limits_env_project_org_fk
        foreign key (environment_id, project_id, organization_id)
        references project_environments(id, project_id, organization_id)
);

insert into environment_limits (environment_id, organization_id, project_id)
    select id, organization_id, project_id from project_environments
    on conflict (environment_id) do nothing;

create table environment_history (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    project_id uuid not null,
    environment_id uuid not null,
    from_status varchar(24),
    to_status varchar(24) not null,
    from_type varchar(24),
    to_type varchar(24) not null,
    action varchar(64) not null,
    actor_user_id uuid references users(id),
    reason varchar(500),
    created_at timestamptz not null default now(),
    constraint environment_history_env_project_org_fk
        foreign key (environment_id, project_id, organization_id)
        references project_environments(id, project_id, organization_id)
);

create index if not exists environment_history_environment_idx
    on environment_history(environment_id, created_at desc);

create or replace function prevent_environment_history_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'environment_history is append-only';
end;
$$;

create trigger environment_history_no_mutation
    before update or delete on environment_history
    for each row execute function prevent_environment_history_mutation();
create index if not exists project_members_user_idx on project_members(user_id, status);