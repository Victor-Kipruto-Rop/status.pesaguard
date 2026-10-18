-- Phase 02 developer organization tenancy foundation.
-- Existing ACTIVE/SUSPENDED organizations remain valid; CLOSED is migrated to DELETED.

alter table organizations drop constraint if exists organizations_status_check;
update organizations set status = 'DELETED' where status = 'CLOSED';

alter table organizations add column if not exists organization_type varchar(32);
update organizations set organization_type = 'DEVELOPER' where organization_type is null;
alter table organizations alter column organization_type set default 'DEVELOPER';
alter table organizations alter column organization_type set not null;
alter table organizations add column if not exists metadata jsonb;
update organizations set metadata = '{}'::jsonb where metadata is null;
alter table organizations alter column metadata set default '{}'::jsonb;
alter table organizations alter column metadata set not null;
alter table organizations add column if not exists verified_at timestamptz;
alter table organizations add column if not exists verification_reference varchar(120);
alter table organizations add column if not exists status_changed_at timestamptz;
update organizations set status_changed_at = updated_at where status_changed_at is null;
alter table organizations alter column status_changed_at set not null;
alter table organizations add column if not exists deleted_at timestamptz;
alter table organizations add constraint organizations_status_check check (status in ('PENDING', 'ACTIVE', 'SUSPENDED', 'DISABLED', 'DELETED'));
alter table organizations add constraint organizations_type_check check (organization_type in ('DEVELOPER', 'ENTERPRISE', 'PARTNER'));
create index if not exists organizations_status_idx on organizations(status, updated_at desc);

alter table organization_memberships add column if not exists updated_at timestamptz;
update organization_memberships set updated_at = created_at where updated_at is null;
alter table organization_memberships alter column updated_at set not null;
alter table organization_memberships add constraint organization_memberships_id_org_user_unique
    unique (id, organization_id, user_id);

create table if not exists organization_security_settings (
    organization_id uuid primary key references organizations(id),
    allowed_auth_methods varchar(512) not null default 'PASSWORD',
    session_ttl_minutes integer not null default 480 check (session_ttl_minutes between 5 and 10080),
    idle_timeout_minutes integer not null default 120 check (idle_timeout_minutes between 5 and 10080),
    max_sessions integer not null default 10 check (max_sessions between 1 and 100),
    credential_min_length integer not null default 12 check (credential_min_length between 12 and 72),
    credential_max_length integer not null default 72 check (credential_max_length between 12 and 72),
    mfa_required boolean not null default false,
    ip_allowlist text not null default '',
    security_event_types text not null default 'LOGIN_FAILURE,MEMBERSHIP_CHANGED,SECURITY_SETTING_CHANGED',
    updated_by uuid references users(id),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint organization_security_credential_length_check check (credential_min_length <= credential_max_length)
);
insert into organization_security_settings (
    organization_id, allowed_auth_methods, session_ttl_minutes, idle_timeout_minutes, max_sessions,
    credential_min_length, credential_max_length, mfa_required, ip_allowlist, security_event_types,
    created_at, updated_at, version
)
select id, 'PASSWORD', 480, 120, 10, 12, 72, false, '',
       'LOGIN_FAILURE,MEMBERSHIP_CHANGED,SECURITY_SETTING_CHANGED',
       current_timestamp, current_timestamp, 0
from organizations
on conflict (organization_id) do nothing;
create index if not exists organization_security_settings_updated_idx
    on organization_security_settings(updated_at desc);

create table if not exists organization_invitations (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    email varchar(320) not null,
    role varchar(24) not null,
    token_hash varchar(64) not null unique,
    status varchar(24) not null,
    expires_at timestamptz not null,
    invited_by uuid not null references users(id),
    accepted_by uuid references users(id),
    accepted_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint organization_invitations_role_check check (role in ('OWNER', 'ADMIN', 'DEVELOPER', 'VIEWER')),
    constraint organization_invitations_email_check check (email = lower(email)),
    constraint organization_invitations_status_check check (status in ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED'))
);
create index if not exists organization_invitations_org_status_idx
    on organization_invitations(organization_id, status, created_at desc);
create index if not exists organization_invitations_email_idx
    on organization_invitations(lower(email), status);
create unique index if not exists organization_invitations_pending_email_idx
    on organization_invitations(organization_id, lower(email)) where status = 'PENDING';
create unique index if not exists organization_memberships_one_active_owner_idx
    on organization_memberships(organization_id) where role = 'OWNER' and status = 'ACTIVE';

create table if not exists organization_membership_history (
    id uuid primary key,
    membership_id uuid not null references organization_memberships(id),
    organization_id uuid not null references organizations(id),
    user_id uuid not null references users(id),
    from_status varchar(24),
    to_status varchar(24) not null,
    from_role varchar(24),
    to_role varchar(24) not null,
    constraint organization_memberships_history_membership_fk foreign key (membership_id, organization_id, user_id)
        references organization_memberships(id, organization_id, user_id),
    constraint organization_memberships_history_status_check check (to_status in ('ACTIVE', 'SUSPENDED', 'REVOKED')),
    constraint organization_memberships_history_role_check check (to_role in ('OWNER', 'ADMIN', 'DEVELOPER', 'VIEWER')),
    actor_user_id uuid references users(id),
    reason varchar(500),
    created_at timestamptz not null
);
create index if not exists organization_membership_history_membership_idx
    on organization_membership_history(membership_id, created_at desc);
create index if not exists organization_membership_history_org_idx
    on organization_membership_history(organization_id, created_at desc);

alter table audit_events add column if not exists hash_version smallint not null default 1;
alter table audit_events add column if not exists correlation_id uuid;
update audit_events set correlation_id = request_id where correlation_id is null;
alter table audit_events alter column correlation_id set not null;
alter table audit_events add column if not exists ip_address varchar(45);
alter table audit_events add column if not exists user_agent varchar(512);
create index if not exists audit_events_correlation_idx
    on audit_events(organization_id, correlation_id, created_at desc);
