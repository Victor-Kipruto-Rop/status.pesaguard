create table organization_invitation_email_deliveries (
    id uuid primary key,
    invitation_id uuid not null references organization_invitations(id) on delete cascade,
    recipient_email varchar(320) not null,
    organization_name varchar(120) not null,
    inviter_name varchar(120) not null,
    invitation_role varchar(24) not null,
    token_hash varchar(64) not null,
    token_ciphertext text,
    expires_at timestamptz not null,
    status varchar(24) not null,
    attempt_count integer not null default 0,
    next_attempt_at timestamptz not null,
    sent_at timestamptz,
    last_error varchar(120),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint organization_invitation_email_delivery_status_check
        check (status in ('PENDING', 'SENT', 'FAILED', 'CANCELLED')),
    constraint organization_invitation_email_delivery_attempts_check
        check (attempt_count >= 0),
    constraint organization_invitation_email_delivery_sent_check
        check ((status = 'SENT' and sent_at is not null and token_ciphertext is null)
            or (status <> 'SENT' and sent_at is null)),
    constraint organization_invitation_email_delivery_ciphertext_check
        check ((status = 'PENDING' and token_ciphertext is not null)
            or (status <> 'PENDING' and token_ciphertext is null))
);

create index organization_invitation_email_delivery_due_idx
    on organization_invitation_email_deliveries(next_attempt_at, created_at)
    where status = 'PENDING';
