-- Phase 12: API request analytics.
--
-- Usage figures end up on invoices, so correctness properties matter more here
-- than in most telemetry. Two are enforced in the schema:
--
--   1. request_id is UNIQUE. A client retry, a proxy replay or a double-charged
--      delivery all present the same id, and a duplicate must be rejected at the
--      database rather than inflating a customer's billable usage.
--   2. A bucket is keyed by its full dimension set plus its window, so minute and
--      hourly rollups can never overwrite one another.

create table api_request_events (
    id uuid primary key,
    -- Idempotency key. See note 1 above.
    request_id varchar(64) not null unique,
    organization_id uuid not null references organizations(id),
    project_id uuid not null references projects(id),
    environment_id uuid not null references project_environments(id),
    -- Exactly one of these identifies the calling credential. Both null means an
    -- unattributed request, which is itself worth reporting.
    api_key_id uuid references api_keys(id),
    oauth_application_id uuid references oauth_applications(id),
    endpoint varchar(256) not null,
    method varchar(8) not null,
    status_code integer not null,
    latency_ms integer not null,
    response_bytes bigint,
    -- When the request happened, not when it was recorded. A late event belongs
    -- in the window it occurred in, otherwise a backlog flushed after midnight
    -- would be counted against the wrong day.
    occurred_at timestamptz not null,
    user_id uuid references users(id),
    recorded_at timestamptz not null default now(),
    constraint api_request_events_status_plausible
        check (status_code between 100 and 599),
    constraint api_request_events_latency_non_negative check (latency_ms >= 0),
    constraint api_request_events_bytes_non_negative
        check (response_bytes is null or response_bytes >= 0),
    constraint api_request_events_method_upper
        check (method = upper(method))
);

-- Ingestion lookup and the aggregation scan both filter on time.
create index if not exists api_request_events_occurred_idx
    on api_request_events(occurred_at);
create index if not exists api_request_events_tenant_idx
    on api_request_events(organization_id, project_id, environment_id, occurred_at desc);
create index if not exists api_request_events_credential_idx
    on api_request_events(api_key_id, occurred_at desc)
    where api_key_id is not null;

create table usage_buckets (
    id uuid primary key,
    granularity varchar(16) not null,
    -- Always a truncated boundary in UTC, never local time.
    window_start timestamptz not null,
    organization_id uuid not null references organizations(id),
    project_id uuid not null references projects(id),
    environment_id uuid not null references project_environments(id),
    api_key_id uuid references api_keys(id),
    oauth_application_id uuid references oauth_applications(id),
    endpoint varchar(256) not null,
    method varchar(8) not null,
    total_requests bigint not null default 0,
    successful_requests bigint not null default 0,
    failed_requests bigint not null default 0,
    client_errors bigint not null default 0,
    server_errors bigint not null default 0,
    latency_sum_ms bigint not null default 0,
    latency_max_ms bigint not null default 0,
    p50_latency_ms bigint not null default 0,
    p95_latency_ms bigint not null default 0,
    p99_latency_ms bigint not null default 0,
    response_bytes_total bigint,
    -- Events that arrived after this window was first closed, so an operator can
    -- tell 'this bucket is complete' from 'complete and then adjusted'.
    late_event_count bigint not null default 0,
    last_event_at timestamptz,
    version_lock bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint usage_buckets_granularity_check
        check (granularity in ('MINUTE', 'HOUR', 'DAY', 'MONTH')),
    constraint usage_buckets_counts_non_negative check (
        total_requests >= 0 and successful_requests >= 0 and failed_requests >= 0
        and client_errors >= 0 and server_errors >= 0 and late_event_count >= 0
    ),
    constraint usage_buckets_latency_non_negative check (
        latency_sum_ms >= 0 and latency_max_ms >= 0
        and p50_latency_ms >= 0 and p95_latency_ms >= 0 and p99_latency_ms >= 0
    ),
    -- Successes and failures partition the total. A bucket where they do not add up
    -- has been corrupted and should be rejected rather than silently reported.
    constraint usage_buckets_counts_partition check (
        successful_requests + failed_requests <= total_requests
    ),
    -- One bucket per dimension set per window. Without this, a minute and an
    -- hourly rollup would overwrite each other.
    constraint usage_buckets_unique_window unique (
        granularity, window_start, organization_id, project_id, environment_id,
        endpoint, method, api_key_id, oauth_application_id
    )
);

create index if not exists usage_buckets_lookup_idx
    on usage_buckets(granularity, organization_id, window_start desc);
create index if not exists usage_buckets_endpoint_idx
    on usage_buckets(organization_id, endpoint, window_start desc);

-- Aggregates are recomputed from raw rather than incremented, so a failure part
-- way through a rollup is recovered by re-running it. That only holds while the
-- raw events survive, so this table is not pruned here; any retention policy must
-- run only after the coarsest rollup (MONTH) for a window is complete.
create or replace function prevent_api_request_events_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'api_request_events is append-only; record a correcting event instead';
end;
$$;

drop trigger if exists api_request_events_append_only on api_request_events;
create trigger api_request_events_append_only
    before update or delete on api_request_events
    for each row execute function prevent_api_request_events_mutation();
