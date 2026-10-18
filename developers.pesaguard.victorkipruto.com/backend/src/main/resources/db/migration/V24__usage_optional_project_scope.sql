-- Usage events for organization-scoped routes.
--
-- api_request_events.project_id was NOT NULL with a foreign key to projects, but a
-- large share of authenticated traffic is not project-scoped: /api/v1/auth/*,
-- /api/v1/organization/* and the session endpoints have no project in their path.
-- UsageTrackingFilter attributes those requests with a null projectId, so every
-- such request produced a not-null violation. The insert failed in its own
-- REQUIRES_NEW transaction and was reported as "usage event rejected", meaning
-- organization-level usage was silently never recorded while the API itself kept
-- working. The failure was invisible precisely because it was asynchronous.
--
-- Null is the correct representation of "this request was not project-scoped",
-- not a defect: inventing a project would misattribute a customer's traffic to
-- an arbitrary project and corrupt per-project billing.
--
-- environment_id stays NOT NULL because the same argument does not hold there: an
-- unattributable request is dropped by the filter before it reaches this table.

alter table api_request_events
    drop constraint if exists api_request_events_project_id_fkey;

alter table api_request_events
    drop constraint if exists api_request_events_project_id_not_null;

alter table api_request_events
    alter column project_id drop not null;

-- Supports the per-project rollup now that the column is nullable: a partial index
-- keeps organization-scoped rows out of the project scan entirely.
create index if not exists api_request_events_project_idx
    on api_request_events(organization_id, project_id, occurred_at desc)
    where project_id is not null;