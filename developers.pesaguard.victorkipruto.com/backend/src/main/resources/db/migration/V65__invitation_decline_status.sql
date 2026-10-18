alter table organization_invitations
    add column if not exists declined_at timestamptz;

alter table organization_invitations
    drop constraint if exists organization_invitations_status_check;

alter table organization_invitations
    add constraint organization_invitations_status_check
    check (status in ('PENDING', 'ACCEPTED', 'DECLINED', 'REVOKED', 'EXPIRED', 'CANCELLED'));
