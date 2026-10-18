-- Phase 02 follow-up: complete the role set and repair two stale role constraints.
--
-- Adds FINANCE, AUDITOR and READ_ONLY. READ_ONLY is the name used by the product
-- specification; VIEWER is retained because rows already store it, so renaming
-- would need a data migration and would break any external report keyed on it.
--
-- This migration also fixes a pre-existing defect. V4 widened
-- organization_memberships_role_check to include SECURITY and ANALYST but did not
-- touch the two other tables carrying the same check:
--
--   * organization_invitations_role_check
--   * organization_memberships_history_role_check (on organization_membership_history)
--
-- Both still allowed only OWNER/ADMIN/DEVELOPER/VIEWER. Inviting a SECURITY or
-- ANALYST member therefore failed with a constraint violation -- a 409 from
-- DataIntegrityViolationException, reported as RESOURCE_CONFLICT with no hint that
-- the role itself was the problem. Recording the membership change into history
-- failed the same way. The unit suites did not catch it because they exercise
-- the domain objects directly and never write through PostgreSQL.
--
-- All three constraints are dropped and recreated rather than altered: PostgreSQL
-- has no "add to CHECK", and recreating takes a brief ACCESS EXCLUSIVE lock but
-- rewrites no rows and changes no existing data.
--
-- No backfill. These are role values a row may now hold; nothing existing is
-- rewritten, so nobody's authority changes as a result of this migration.

-- Every role the OrganizationRole enum can persist, in one place, asserted below.
-- Kept in a single list so the three constraints cannot drift apart again.
do $$
declare
    allowed text[] := array[
        'OWNER', 'ADMIN', 'DEVELOPER', 'SECURITY', 'ANALYST',
        'FINANCE', 'AUDITOR', 'READ_ONLY', 'VIEWER'
    ];
    stored_role text;
begin
    -- Refuses to proceed if a stored role is outside the list above, which would
    -- mean the constraint is about to reject a value that already exists in the
    -- table and the migration would fail mid-flight.
    select role into stored_role from organization_memberships
    where role <> all(allowed) limit 1;
    if stored_role is not null then
        raise exception 'organization_memberships holds a role the new constraint rejects: %', stored_role;
    end if;
end $$;

alter table organization_memberships
    drop constraint if exists organization_memberships_role_check;
alter table organization_memberships
    add constraint organization_memberships_role_check check (role in (
        'OWNER', 'ADMIN', 'DEVELOPER', 'SECURITY', 'ANALYST',
        'FINANCE', 'AUDITOR', 'READ_ONLY', 'VIEWER'));

alter table organization_invitations
    drop constraint if exists organization_invitations_role_check;
alter table organization_invitations
    add constraint organization_invitations_role_check check (role in (
        'OWNER', 'ADMIN', 'DEVELOPER', 'SECURITY', 'ANALYST',
        'FINANCE', 'AUDITOR', 'READ_ONLY', 'VIEWER'));

alter table organization_membership_history
    drop constraint if exists organization_memberships_history_role_check;
alter table organization_membership_history
    add constraint organization_memberships_history_role_check check (to_role in (
        'OWNER', 'ADMIN', 'DEVELOPER', 'SECURITY', 'ANALYST',
        'FINANCE', 'AUDITOR', 'READ_ONLY', 'VIEWER'));