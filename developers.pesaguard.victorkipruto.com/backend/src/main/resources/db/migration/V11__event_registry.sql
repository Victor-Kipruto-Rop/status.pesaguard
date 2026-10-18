-- Phase 11: event registry, subscriptions and delivery tracking.
--
-- An event registry is the platform's contract with integrators. Once a name is
-- published and someone subscribes to it, changing what it means or stopping it
-- early breaks a live integration. Three rules follow, and the schema enforces
-- them:
--
--   1. Names are grammar-checked, because they reach delivery headers, log lines
--      and metric labels. A name with a newline in it is a log-injection and
--      metric-poisoning hazard.
--   2. A subscription pins an event version, so an additive schema change cannot
--      silently alter what a subscriber parses.
--   3. Deprecation carries a sunset date, and retirement is refused before it.
--      Silently stopping a published event is an outage, not a deprecation.

create table event_types (
    -- Canonical dotted name, e.g. 'developer.project.created'.
    name varchar(128) primary key,
    namespace varchar(32) not null,
    entity varchar(32) not null,
    action varchar(32) not null,
    description varchar(500) not null,
    category varchar(24) not null,
    -- Pinned by subscribers.
    version integer not null,
    -- JSON Schema for this version's payload.
    schema text not null,
    lifecycle varchar(24) not null,
    -- Set when deprecated: the event to migrate to.
    replaced_by varchar(128),
    -- The last date the event is emitted. Enforced, not advisory.
    sunset_at timestamptz,
    change_reason varchar(500),
    version_lock bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    -- Same grammar as EventType in code: three or four segments, so
    -- 'developer.webhook.delivery.failed' (a nested entity path) is valid, as are
    -- the underscore in 'developer.api_key.created'.
    constraint event_types_name_format check (
        name ~ ('^' || '[a-z][a-z0-9_-]{1,31}' || '\.'
            || '[a-z][a-z0-9_-]{1,31}' || '\.'
            || '[a-z][a-z0-9_-]{1,31}' || '(\.[a-z][a-z0-9_-]{1,31})?$')
    ),
    -- The stored segments must agree with the name, so a row cannot claim to be
    -- one event while being indexed as another.
    constraint event_types_segments_match check (name = namespace || '.' || entity || '.' || action),
    constraint event_types_lifecycle_check
        check (lifecycle in ('DRAFT', 'ACTIVE', 'DEPRECATED', 'RETIRED')),
    constraint event_types_category_check
        check (category in ('PLATFORM', 'CREDENTIALS', 'WEBHOOKS', 'SANDBOX', 'SECURITY')),
    constraint event_types_version_positive check (version >= 1),
    constraint event_types_cannot_replace_itself check (replaced_by is null or replaced_by <> name),
    -- A deprecated event must say when it stops and what to use instead, so
    -- subscribers are not left guessing.
    constraint event_types_deprecation_is_complete
        check (lifecycle <> 'DEPRECATED' or (replaced_by is not null and sunset_at is not null))
);

create index if not exists event_types_namespace_idx on event_types(namespace);
create index if not exists event_types_lifecycle_idx on event_types(lifecycle);
-- Seed catalog. Insert-only, so an operator's annotation is not overwritten.
insert into event_types (name, namespace, entity, action, description, category,
                         version, schema, lifecycle)
values
    ('developer.project.created', 'developer', 'project', 'created',
     'A project was created in the developer platform.', 'PLATFORM', 1,
     '{"type":"object","required":["id","name"]}', 'ACTIVE'),
    ('developer.project.updated', 'developer', 'project', 'updated',
     'A project name, settings or membership changed.', 'PLATFORM', 1,
     '{"type":"object","required":["id","changed"]}', 'ACTIVE'),
    ('developer.project.deleted', 'developer', 'project', 'deleted',
     'A project was deleted.', 'PLATFORM', 1, '{"type":"object","required":["id"]}', 'ACTIVE'),
    ('developer.api_key.created', 'developer', 'api_key', 'created',
     'An API key was issued. The secret is never included.', 'CREDENTIALS', 1,
     '{"type":"object","required":["id","name"]}', 'ACTIVE'),
    ('developer.api_key.revoked', 'developer', 'api_key', 'revoked',
     'An API key was revoked and can no longer authenticate.', 'CREDENTIALS', 1,
     '{"type":"object","required":["id","reason"]}', 'ACTIVE'),
    ('developer.webhook.created', 'developer', 'webhook', 'created',
     'A webhook endpoint was registered. The signing secret is never included.', 'WEBHOOKS', 1,
     '{"type":"object","required":["id","endpoint"]}', 'ACTIVE'),
    ('developer.webhook.delivery.failed', 'developer', 'webhook.delivery', 'failed',
     'A webhook delivery exhausted its retry budget.', 'WEBHOOKS', 1,
     '{"type":"object","required":["endpoint","attempts"]}', 'ACTIVE'),
    ('developer.sandbox.created', 'developer', 'sandbox', 'created',
     'A sandbox was created and pinned to a sandbox environment.', 'SANDBOX', 1,
     '{"type":"object","required":["id","environmentId"]}', 'ACTIVE'),
    ('developer.sandbox.expired', 'developer', 'sandbox', 'expired',
     'A sandbox passed its expiry and stopped being executable.', 'SANDBOX', 1,
     '{"type":"object","required":["id"]}', 'ACTIVE'),
    ('developer.organization.member_added', 'developer', 'organization', 'member_added',
     'A member was added to an organization.', 'SECURITY', 1,
     '{"type":"object","required":["userId","role"]}', 'ACTIVE'),
    ('developer.access.denied', 'developer', 'access', 'denied',
     'An API access decision denied a request.', 'SECURITY', 1,
     '{"type":"object","required":["reasonCode","scope"]}', 'ACTIVE')
on conflict (name) do nothing;

create table event_subscriptions (
    id uuid primary key,
    organization_id uuid not null references organizations(id),
    project_id uuid not null references projects(id),
    -- Null means every environment in the project.
    environment_id uuid,
    endpoint_id varchar(128) not null,
    event_type varchar(128) not null references event_types(name),
    -- Pinned: a v2 event must not change a v1 subscriber's payload.
    event_version integer not null,
    -- Attribute filters as 'key=value' pairs joined by newline.
    filters text not null default '',
    status varchar(24) not null,
    description varchar(500),
    version_lock bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint event_subscriptions_status_check
        check (status in ('ACTIVE', 'SUSPENDED', 'CANCELLED')),
    constraint event_subscriptions_version_positive check (event_version >= 1),
    -- A subscription may not filter on its own tenant. That is exactly what would
    -- let a subscriber reach another organization's events.
    constraint event_subscriptions_filters_exclude_tenant check (
        filters !~* '(^|\n)[[:space:]]*organizationId[[:space:]]*='
    )
);

create table event_deliveries (
    id uuid primary key,
    event_id uuid not null,
    event_type varchar(128) not null,
    subscription_id uuid not null references event_subscriptions(id) on delete cascade,
    -- Carried on the row rather than joined: a delivery record readable across
    -- tenants would leak that a given resource changed.
    organization_id uuid not null references organizations(id),
    project_id uuid not null references projects(id),
    environment_id uuid,
    endpoint_id varchar(128) not null,
    -- 1-based. Each attempt is a new row, so the sequence survives.
    attempt integer not null,
    status varchar(24) not null,
    response_code integer,
    -- Excerpt only: this table must not become a second copy of payloads that
    -- nobody thinks to apply retention to.
    response_excerpt varchar(512),
    error_code varchar(64),
    latency_ms integer,
    next_attempt_at timestamptz,
    created_at timestamptz not null default now(),
    constraint event_deliveries_status_check check (
        status in ('PENDING', 'IN_FLIGHT', 'DELIVERED', 'RETRY_SCHEDULED',
                   'FAILED', 'DEAD_LETTERED', 'SUPPRESSED')
    ),
    constraint event_deliveries_attempt_positive check (attempt >= 1),
    constraint event_deliveries_latency_non_negative check (latency_ms is null or latency_ms >= 0),
    -- One attempt number per event/subscription pair. Stops two concurrent retries
    -- recording the same attempt twice.
    constraint event_deliveries_attempt_unique unique (event_id, subscription_id, attempt)
);

create index if not exists event_deliveries_event_idx
    on event_deliveries(event_id, created_at desc);
create index if not exists event_deliveries_dlq_idx
    on event_deliveries(organization_id, status, created_at desc)
    where status = 'DEAD_LETTERED';

-- The DLQ is evidence that a delivery failed and someone must look. It must not
-- be editable afterwards, including by an operator clearing 'noisy' rows.
create or replace function prevent_event_deliveries_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'event_deliveries is append-only';
end;
$$;

drop trigger if exists event_deliveries_append_only on event_deliveries;
create trigger event_deliveries_append_only
    before update or delete on event_deliveries
    for each row execute function prevent_event_deliveries_mutation();

create index if not exists event_subscriptions_dispatch_idx
    on event_subscriptions(event_type, status);
create index if not exists event_subscriptions_tenant_idx
    on event_subscriptions(organization_id, project_id);
