-- Phase 09 (continued): strengthen the sandbox isolation guarantee in the schema.
--
-- V9 recorded the environment type as a string and checked it was 'SANDBOX'. That
-- is necessary but not sufficient: a row could still name an environment that
-- belongs to a different tenant, or that is not a sandbox at all, as long as the
-- type column was written honestly by a buggy caller.
--
-- This migration makes the database itself refuse such a row, so isolation holds
-- even against a direct psql session or a migration that never loads application
-- code.
--
-- The composite foreign key requires a unique key on
-- project_environments(id, organization_id, project_id), which did not exist.
-- V3 only guaranteed uniqueness on (project_id, type).

create unique index if not exists project_environments_tenant_unique
    on project_environments(id, organization_id, project_id);

-- The guard row must name a real environment, in the same tenant and project.
alter table sandbox_isolation_guards
    drop constraint if exists sandbox_isolation_guards_environment_fk;

alter table sandbox_isolation_guards
    add constraint sandbox_isolation_guards_environment_fk
    foreign key (environment_id, organization_id, project_id)
    references project_environments(id, organization_id, project_id);

-- Same guarantee on the sandbox row itself.
alter table sandboxes
    drop constraint if exists sandboxes_environment_fk;

alter table sandboxes
    add constraint sandboxes_environment_fk
    foreign key (environment_id, organization_id, project_id)
    references project_environments(id, organization_id, project_id);

-- And on every execution row, so the audit trail cannot describe a sandbox
-- execution against an environment outside its tenant either.
alter table sandbox_executions
    drop constraint if exists sandbox_executions_environment_fk;

alter table sandbox_executions
    add constraint sandbox_executions_environment_fk
    foreign key (environment_id, organization_id, project_id)
    references project_environments(id, organization_id, project_id);