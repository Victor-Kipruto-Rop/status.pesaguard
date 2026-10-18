-- Transactional outbox.
--
-- The pattern exists to solve one problem: a domain change and the event that
-- announces it must both succeed or both fail. Writing to PostgreSQL and then
-- publishing to Redpanda is two independent commits. Between them there is a
-- window where the transaction has committed but the broker call has not
-- happened, so a crash loses the event permanently -- and the customer's API key
-- was already revoked, while no downstream system ever heard about it.
--
-- The reverse is worse. Publishing first and then failing to commit announces an
-- event for a change that never happened, which for a financial platform means
-- consumers acting on a revocation or a refund that does not exist.
--
-- The outbox removes the window: the event is written to this table in the SAME
-- transaction as the domain change, so it is atomic with the change itself. A
-- publisher then relays rows to the broker and marks them published. At-least-once
-- delivery is the guarantee, so consumers must be idempotent; exactly-once across
-- two systems is not achievable without a distributed transaction, which is why
-- none is attempted here.
--
-- partition_key is stored explicitly rather than derived at publish time: routing
-- must be decided by the domain that emitted the event, and re-deriving it later
-- would let a rename silently change which partition an event lands in, breaking
-- per-tenant ordering for events already in flight.

create table outbox_events (
    id uuid primary key,
    -- Immutable across retries. A retried publish reuses this id, so a consumer
    -- that has seen it can discard the duplicate.
    event_id uuid not null unique,
    event_type varchar(128) not null,
    event_version integer not null default 1,
    organization_id uuid references organizations(id),
    project_id uuid references projects(id),
    -- Routing key. Not null so a missing routing decision fails loudly at write
    -- time rather than producing an unroutable message at publish time.
    partition_key varchar(128) not null,
    correlation_id varchar(64),
    trace_id varchar(64),
    -- The event body, stored as text and constrained to be valid JSON.
    --
    -- jsonb was the natural type, but PostgreSQL refuses an implicit varchar to
    -- jsonb conversion, so binding a Java String fails at the driver. The CHECK
    -- below does the validation instead, preserving the guarantee that matters:
    -- malformed JSON is rejected by the database and never stored.
    payload text not null,
    source varchar(64) not null default 'developer-platform',
    status varchar(24) not null default 'PENDING',
    attempt_count integer not null default 0,
    -- Earliest time the next attempt is permitted. Backoff is computed by the
    -- publisher, not by the database, so retry policy stays in code.
    next_attempt_at timestamptz not null default now(),
    published_at timestamptz,
    -- Truncated after publication. An error message is operational data, not a
    -- record of what the event was.
    last_error varchar(500),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint outbox_events_status_check
        check (status in ('PENDING', 'PUBLISHED', 'DEAD_LETTERED')),
    constraint outbox_events_attempt_non_negative check (attempt_count >= 0),
    constraint outbox_events_version_positive check (event_version >= 1),
    -- jsonb_typeof raises on input that is not valid JSON, so this rejects a
    -- malformed body at write time instead of storing it as opaque text.
    constraint outbox_events_payload_is_json
        check (jsonb_typeof(payload::jsonb) is not null),
    -- A published row must say when. Without this a row could be marked
    -- published with no timestamp and would be indistinguishable from one that
    -- was never attempted.
    constraint outbox_events_published_consistent check (
        (status = 'PUBLISHED' and published_at is not null)
        or (status <> 'PUBLISHED' and published_at is null)
    )
);

-- The publisher's claim query: unpublished rows whose backoff has elapsed,
-- oldest first. Partial on status so the index stays small as published rows
-- accumulate, which is the overwhelming majority over time.
create index if not exists outbox_events_claim_idx
    on outbox_events(next_attempt_at, created_at)
    where status = 'PENDING';

-- Retention sweep over published rows.
create index if not exists outbox_events_published_idx
    on outbox_events(published_at)
    where status = 'PUBLISHED';

-- DLQ inspection by tenant, so an operator can answer "what failed for this
-- customer" without scanning every dead letter in the platform.
create index if not exists outbox_events_dead_letter_idx
    on outbox_events(organization_id, created_at desc)
    where status = 'DEAD_LETTERED';
