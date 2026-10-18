-- Phase 04: developer RBAC and permissions.
--
-- Built-in organization roles are defined in code (OrganizationRole) and remain
-- authoritative. This migration adds custom roles, custom role assignments, and
-- production access requests.
--
-- No backfill is required or performed: built-in role permissions are resolved
-- from code, so every existing member keeps exactly the authority they already
-- had. Inventing role rows here would risk silently widening someone's access.

alter table organization_memberships drop constraint if exists organization_memberships_role_check;
alter table organization_memberships add constraint organization_memberships_role_check
    check (role in ('OWNER', 'ADMIN', 'DEVELOPER', 'SECURITY', 'ANALYST', 'VIEWER'));

-- Custom, organization-scoped roles.
create table organization_roles (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    name varchar(64) not null,
    description varchar(500),
    permissions text not null default '',
    status varchar(24) not null,
    created_by uuid not null references users(id),
    updated_by uuid references users(id),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint organization_roles_status_check check (status in ('ACTIVE', 'ARCHIVED')),
    constraint organization_roles_name_unique unique (organization_id, name),
    constraint organization_roles_name_not_blank check (length(btrim(name)) > 0)
);

create index if not exists organization_roles_organization_idx
    on organization_roles(organization_id, status, created_at desc);

-- A member may hold several custom roles on top of their built-in role.
-- Assignments are revoked, never deleted, so role history stays auditable.
create table organization_role_assignments (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    user_id uuid not null references users(id),
    role_id uuid not null references organization_roles(id),
    assigned_by uuid not null references users(id),
    assigned_at timestamptz not null default now(),
    revoked_at timestamptz,
    revoked_by uuid references users(id),
    constraint organization_role_assignments_unique unique (organization_id, user_id, role_id),
    constraint organization_role_assignments_revoke_check
        check ((revoked_at is null and revoked_by is null) or (revoked_at is not null))
);

create index if not exists organization_role_assignments_user_idx
    on organization_role_assignments(organization_id, user_id)
    where revoked_at is null;

-- Production access is an explicit, reviewed request. The reviewer must not be
-- the requester; the database refuses to record a self-approval.
create table production_access_requests (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    project_id uuid not null,
    environment_id uuid not null,
    requested_by uuid not null references users(id),
    reason varchar(1000) not null,
    status varchar(24) not null,
    reviewed_by uuid references users(id),
    reviewed_at timestamptz,
    review_note varchar(1000),
    expires_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint production_access_requests_status_check
        check (status in ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED')),
    constraint production_access_requests_review_check
        check (status in ('PENDING', 'CANCELLED') or reviewed_at is not null),
    constraint production_access_requests_self_review_check
        check (reviewed_by is null or reviewed_by <> requested_by),
    constraint production_access_requests_env_fk
        foreign key (environment_id, project_id, organization_id)
        references project_environments(id, project_id, organization_id)
);

create index if not exists production_access_requests_pending_idx
    on production_access_requests(organization_id, status, created_at desc);