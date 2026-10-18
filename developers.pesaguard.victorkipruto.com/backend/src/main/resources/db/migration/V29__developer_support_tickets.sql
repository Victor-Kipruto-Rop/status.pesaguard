create table support_tickets (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    user_id uuid not null references users(id),
    contact_email varchar(320) not null,
    category varchar(32) not null,
    subject varchar(180) not null,
    description varchar(8000) not null,
    priority varchar(16) not null,
    status varchar(24) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint support_tickets_category_check
        check (category in ('API_ISSUE', 'BUG_REPORT', 'ACCOUNT_ACCESS', 'SECURITY', 'PRODUCTION', 'OTHER')),
    constraint support_tickets_priority_check
        check (priority in ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    constraint support_tickets_status_check
        check (status = 'OPEN'),
    constraint support_tickets_subject_present check (btrim(subject) <> ''),
    constraint support_tickets_description_present check (btrim(description) <> '')
);

create index support_tickets_requester_idx
    on support_tickets (organization_id, user_id, updated_at desc);
