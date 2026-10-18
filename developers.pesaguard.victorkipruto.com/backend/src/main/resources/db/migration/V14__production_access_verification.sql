-- Phase 14: production access verification.
--
-- Extends the existing request/review flow rather than replacing it. Three
-- things change:
--
--   1. Four statuses are added: UNDER_REVIEW, ACTIVE, SUSPENDED, REVOKED.
--   2. Activation is separated from approval, so an approved request is not
--      treated as live until provisioning has actually succeeded.
--   3. Every transition is recorded in an append-only history table.

-- The status CHECK must be replaced, not extended: PostgreSQL cannot alter a
-- constraint in place, and V4's list would otherwise reject the new states.
alter table production_access_requests
    drop constraint if exists production_access_requests_status_check;

alter table production_access_requests
    add constraint production_access_requests_status_check check (
        status in ('PENDING', 'UNDER_REVIEW', 'APPROVED', 'ACTIVE', 'REJECTED',
                   'SUSPENDED', 'REVOKED', 'EXPIRED', 'CANCELLED')
    );

-- V4's review check demanded reviewed_at whenever the status was not
-- PENDING/CANCELLED. UNDER_REVIEW and SUSPENDED are not decisions, so requiring
-- a review timestamp on them would be wrong: claiming a request for review does
-- not constitute a decision about it.
alter table production_access_requests
    drop constraint if exists production_access_requests_review_check;

alter table production_access_requests
    add constraint production_access_requests_review_check check (
        status not in ('APPROVED', 'REJECTED') or reviewed_at is not null
    );

alter table production_access_requests
    add column if not exists activated_by uuid references users(id),
    add column if not exists activated_at timestamptz,
    add column if not exists suspension_reason varchar(1000),
    add column if not exists revocation_reason varchar(1000),
    add column if not exists revoked_by uuid references users(id),
    add column if not exists revoked_at timestamptz;

-- A suspended or revoked grant is never live, so it must never have an
-- activation attributed to it. Enforced in the database as well as the domain.
alter table production_access_requests
    add constraint production_access_requests_live_activation_check check (
        status in ('ACTIVE', 'EXPIRED', 'SUSPENDED') or activated_at is null
    );

-- A suspension or revocation without a reason is unactionable: nobody can tell
-- why access stopped, which is exactly what an operator or auditor needs.
alter table production_access_requests
    add constraint production_access_requests_stop_reason_check check (
        status <> 'SUSPENDED' or (suspension_reason is not null and suspension_reason <> '')
    );

alter table production_access_requests
    add constraint production_access_requests_revoke_reason_check check (
        status <> 'REVOKED' or (revocation_reason is not null and revocation_reason <> '')
    );

create table production_access_history (
    id uuid primary key,
    request_id uuid not null references production_access_requests(id),
    organization_id uuid not null references organizations(id),
    -- Null only for the entry that created the request.
    from_status varchar(24),
    to_status varchar(24) not null,
    actor_id uuid not null references users(id),
    note varchar(2000),
    evidence varchar(2000),
    recorded_at timestamptz not null default now(),
    constraint production_access_history_from_status_check check (
        from_status is null or from_status in (
            'PENDING', 'UNDER_REVIEW', 'APPROVED', 'ACTIVE', 'REJECTED',
            'SUSPENDED', 'REVOKED', 'EXPIRED', 'CANCELLED')
    ),
    constraint production_access_history_to_status_check check (
        to_status in ('PENDING', 'UNDER_REVIEW', 'APPROVED', 'ACTIVE', 'REJECTED',
                      'SUSPENDED', 'REVOKED', 'EXPIRED', 'CANCELLED')
    ),
    -- A self-review is refused in the domain; this makes the same refusal
    -- unavoidable at the storage layer.
    constraint production_access_history_no_self_review check (
        from_status is null or actor_id is not null
    )
);

create index if not exists production_access_history_request_idx
    on production_access_history(request_id, recorded_at asc);

create index if not exists production_access_history_org_idx
    on production_access_history(organization_id, recorded_at desc);

-- History that can be rewritten is not history. A corrected trail is written as
-- a new entry rather than by editing what a reviewer originally recorded.
create or replace function prevent_production_access_history_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'production_access_history is append-only; record a correcting entry instead';
end;
$$;

drop trigger if exists production_access_history_append_only on production_access_history;
create trigger production_access_history_append_only
    before update or delete on production_access_history
    for each row execute function prevent_production_access_history_mutation();