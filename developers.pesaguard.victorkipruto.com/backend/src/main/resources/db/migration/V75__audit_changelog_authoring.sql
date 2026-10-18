create table platform_changelog_entry_audit (
    id uuid primary key,
    entry_id uuid not null references platform_changelog_entries(id) on delete restrict,
    operator_id uuid not null,
    operator_subject text not null,
    action varchar(24) not null,
    reason varchar(500) not null,
    occurred_at timestamptz not null default now(),
    constraint platform_changelog_audit_action_check
        check (action in ('DRAFT_CREATED', 'DRAFT_UPDATED', 'PUBLISHED')),
    constraint platform_changelog_audit_reason_check
        check (length(btrim(reason)) between 1 and 500)
);

create index platform_changelog_entry_audit_history_idx
    on platform_changelog_entry_audit(entry_id, occurred_at desc, id desc);
