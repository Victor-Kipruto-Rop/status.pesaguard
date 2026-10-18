-- Prevent concurrent invitation acceptance, revocation, and token rotation
-- from overwriting one another.
alter table organization_invitations
    add column if not exists version bigint not null default 0;
