alter table organization_invitations
    add column idempotency_key_hash varchar(64),
    add column idempotency_request_hash varchar(64);

create unique index organization_invitations_create_idempotency_idx
    on organization_invitations(organization_id, invited_by, idempotency_key_hash)
    where idempotency_key_hash is not null;
