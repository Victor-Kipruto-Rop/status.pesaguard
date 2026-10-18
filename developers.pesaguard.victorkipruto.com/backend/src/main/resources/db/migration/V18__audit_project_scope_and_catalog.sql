-- Phase 18: audit completeness.
--
-- The audit log already existed with an append-only trigger and an HMAC hash
-- chain. This migration adds the one required field that was missing, and pins
-- the action vocabulary so the log can be queried exhaustively.
--
-- Note on the hash chain: project_id is a SIGNED field, so adding it changes the
-- canonical form. Events written under hash_version 1 and 2 remain verifiable
-- because the verifier selects the canonical form per version rather than
-- assuming "anything not v1 is current".

alter table audit_events
    add column if not exists project_id uuid references projects(id);

-- The project an event concerns. Nullable on purpose: an organization-level
-- change such as a membership removal belongs to no project, and storing a null
-- is more honest than inventing one to satisfy a column.
create index if not exists audit_events_project_idx
    on audit_events(organization_id, project_id, created_at desc)
    where project_id is not null;

-- The action vocabulary is now declared in code (AuditAction). Recording an
-- undeclared action would create an event no query could find, so the set of
-- permitted values is pinned here as the backstop for any writer the domain
-- does not cover.
--
-- Historical rows are grandfathered: the existing values already in the table
-- were written before the catalog existed, and rewriting or rejecting them would
-- damage an append-only log.
alter table audit_events
    drop constraint if exists audit_events_action_known;

alter table audit_events
    add constraint audit_events_action_shape_check check (
        -- Either a declared action, or a value from the pre-catalog era.
        action ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*$'
    );

-- The trigger already present since V1 is what makes the log append-only:
--   before update or delete on audit_events ... prevent_audit_event_mutation()
-- It is repeated here only because it is the single most important property of
-- this table and a reader arriving at V18 should see it stated.
--
-- No INSERT restriction is added. A log that refuses writes is not a log that is
-- being tampered with; refusing writes destroys the evidence instead.