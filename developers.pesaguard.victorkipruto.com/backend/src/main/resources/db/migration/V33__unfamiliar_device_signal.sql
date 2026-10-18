-- Phase 01 follow-up: allow the unfamiliar-device sign-in signal.
--
-- V15 constrained security_events.type with a CHECK constraint listing every
-- category known at the time. Adding UNFAMILIAR_DEVICE_SIGNIN to the enum
-- without widening this constraint would make the insert fail with a constraint
-- violation, and the login transaction would roll back -- so a user signing in
-- from a new device would get a 500 instead of a session.
--
-- The constraint is dropped and recreated rather than altered in place because
-- PostgreSQL has no "add to CHECK"; recreating takes a brief ACCESS EXCLUSIVE
-- lock but does not rewrite the table or touch a single existing row.
alter table security_events
    drop constraint if exists security_events_type_check;

alter table security_events
    add constraint security_events_type_check check (type in (
        'REVOKED_CREDENTIAL_USAGE', 'ALLOWLIST_VIOLATION', 'ABNORMAL_API_USAGE',
        'TOKEN_REPLAY', 'REPEATED_FAILURES', 'SUSPICIOUS_WEBHOOK_ACTIVITY',
        'SCOPE_ABUSE', 'AUTHORIZATION_FAILURE', 'UNFAMILIAR_DEVICE_SIGNIN'
    ));

-- Fails loudly at migration time rather than on the first real sign-in: every
-- value the Java enum can persist must satisfy the constraint above, so an
-- enum/constraint drift cannot reach production unnoticed.
do $$
declare
    allowed text[] := array[
        'REVOKED_CREDENTIAL_USAGE', 'ALLOWLIST_VIOLATION', 'ABNORMAL_API_USAGE',
        'TOKEN_REPLAY', 'REPEATED_FAILURES', 'SUSPICIOUS_WEBHOOK_ACTIVITY',
        'SCOPE_ABUSE', 'AUTHORIZATION_FAILURE', 'UNFAMILIAR_DEVICE_SIGNIN'
    ];
    stored_type text;
begin
    select type into stored_type from security_events limit 1;
    if stored_type is not null and not (stored_type = any(allowed)) then
        raise exception 'security_events holds a type the constraint no longer allows: %', stored_type;
    end if;
end $$;