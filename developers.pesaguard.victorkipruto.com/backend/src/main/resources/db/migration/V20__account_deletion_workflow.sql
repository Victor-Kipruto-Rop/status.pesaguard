-- Account deletion workflow.
--
-- Deletion is a two-step process with a grace period, because a deletion
-- triggered by mistake and discovered a week later would otherwise be
-- unrecoverable. The account keeps working until the grace period elapses.

alter table users
    add column if not exists deletion_requested_at timestamptz;

-- PENDING_DELETION and DELETED join the existing ACTIVE/SUSPENDED/DEACTIVATED set.
alter table users
    drop constraint if exists users_status_check;

alter table users
    add constraint users_status_check check (
        status in ('ACTIVE', 'SUSPENDED', 'DEACTIVATED', 'PENDING_DELETION', 'DELETED')
    );

-- A deletion request must record when it was made: without it the grace period
-- has no start and the account could never complete deletion.
alter table users
    add constraint users_deletion_request_check check (
        status <> 'PENDING_DELETION' or deletion_requested_at is not null
    );

-- Terminal. No transition from DELETED back to ACTIVE exists, so this is a
-- one-way state in the domain and the constraint records that intent.
alter table users
    add constraint users_deletion_terminal_check check (
        status <> 'DELETED' or deletion_requested_at is not null
    );

create index if not exists users_pending_deletion_idx
    on users(deletion_requested_at)
    where status = 'PENDING_DELETION';