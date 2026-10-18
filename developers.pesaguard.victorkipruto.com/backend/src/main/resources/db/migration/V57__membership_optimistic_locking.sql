-- Membership updates alter authorization. Version them so simultaneous role or
-- revocation changes cannot silently overwrite each other.
alter table organization_memberships
    add column if not exists version bigint not null default 0;

alter table project_members
    add column if not exists version bigint not null default 0;
