# Outbound Webhook Delivery

## Destination and connection security

Webhook destinations must use HTTPS and resolve to globally routable unicast
addresses. Localhost, `.localhost`, `.local`, and `.internal` names are
rejected. Every A/AAAA answer is checked; a single private, reserved,
link-local, multicast, shared-address, or otherwise non-global answer rejects
the destination. This includes IPv4 and IPv6 cloud metadata/link-local ranges.

Delivery resolves the hostname again immediately before dispatch and pins the
socket connection to one of those exact validated IP addresses. The TLS SNI,
certificate hostname check, and HTTP `Host` value remain the configured
hostname. The transport uses a direct HTTPS connection and does not consult
`HTTP_PROXY`, `HTTPS_PROXY`, or other environment proxy settings. TLS
certificate and hostname verification remain enabled. No 3xx response is
followed; redirects are terminal failures to avoid forwarding event data to an
unvalidated target. URL fragments and embedded credentials are rejected.

DNS A/AAAA resolution uses the host-configured DNS servers with one shared
three-second deadline; failure or timeout rejects delivery. The HTTP operation
has a 1–30 second total timeout per attempt, with connect and read bounded by
the same timeout. At most 500 bytes of the response body are retained, and
outbound event JSON is capped at 1 MiB. Network egress policy should
independently block private, link-local, and metadata destinations;
application-level checks are not a replacement for egress controls.

## Attempts, durability, and receiver idempotency

Configured limits are 1–5 attempts and 1–30 seconds per request. Retryable
failures are connection errors/timeouts, HTTP 429, and HTTP 5xx. Other 4xx and
all 3xx responses are terminal. Retries use full jitter in
`[0, min(2^attempt, 8)]` seconds. The maximum retry schedule is 5 requests ×
30 seconds plus 1+2+4+8 seconds of backoff: at most 165 seconds plus a
three-second DNS budget, excluding database latency.

A `pending` `WebhookDelivery` row commits before dispatch. Immediately before
each outbound request, the attempt count and `processing` state commit. The
completion status and response metadata commit before success/failure is
returned. If the process crashes during an HTTP request or result persistence,
the durable record remains `processing`; the receiver may or may not have
accepted it.

Run the Alembic migrations before enabling delivery or the recovery worker.
The recovery migration creates `webhook_deliveries` when earlier revisions
skipped the absent table, installs its recovery and tenant lookup indexes,
and enforces tenant row-level security on PostgreSQL. The migration was
verified through a disposable PostgreSQL Docker upgrade/downgrade/re-upgrade;
that check is not a substitute for applying and validating migrations in the
target deployment.

`webhook_operations_worker.py` provides a one-shot recovery and monitoring
tick. Run it from a supervised scheduler at a short, fixed cadence (for
example, once per minute):

```text
python -m pesaguard_backend_pipeline.webhook_operations_worker --batch-size 50 --max-age-seconds 900
```

The worker enumerates active tenants only through the isolated,
read-only `PESAGUARD_TENANT_REGISTRY_DATABASE_URL` role. It processes business
records one tenant scope at a time using `DATABASE_URL` and the transaction
local RLS context. Production execution requires `REDIS_URL`; recovery acquires
a cross-worker lock per delivery and refuses to resend a record whose persisted
attempt counter has consumed the configured budget. Such ambiguous outcomes
become terminal failures requiring operator review. The worker exits nonzero
on database, registry, lock, or recovery errors so the scheduler can alert.
Configure and verify the scheduler's failure alert separately; the repository
does not prove an external scheduler is deployed.

Recovery is at-least-once: a crash after the receiver accepts a request but
before the sender persists the response can cause a replay. Receivers must
persist `X-Webhook-Event-Id` idempotently before applying side effects. The
sender cannot enforce receiver idempotency. Recovery rows are eligible only
after their `updated_at` is older than the configured threshold; the default is
900 seconds. DNS lookups have a three-second resolver-level lifetime; network
and database operations still need deployment-level timeouts and observation.

`X-Webhook-Event-Id` remains stable across retries and duplicate dispatches
when payloads include a scalar `event_id`. If absent, it is derived from the
tenant, endpoint, event type, and canonical body. Receivers must persist this
ID in an idempotency table before applying side effects. The sender does not
provide exactly-once delivery.

## Payload signature and receiver verification

The sender serializes the JSON object once as compact UTF-8 JSON with sorted
keys, no ASCII escaping, and non-finite numbers rejected. Those exact bytes are
both transmitted and signed. The signature is:

```text
X-Webhook-Timestamp: <unix-seconds>
X-Webhook-Signature: t=<unix-seconds>,v1=<lowercase HMAC-SHA256 hex>
HMAC key: per-webhook whsec_ secret
signed bytes: ASCII(timestamp) + "." + raw HTTP request body
```

Receivers must verify the signature over the raw body before parsing JSON,
reject malformed/non-object JSON, compare HMACs in constant time, and enforce
a timestamp tolerance (the included reference verifier uses 300 seconds and
rejects configured tolerances over one hour).
They should persist accepted timestamps/event IDs to prevent replay and
duplicate side effects. `verify_webhook_signature` is a local reference helper;
it does not constitute an inbound receiver route or make receiver-side replay
storage automatic.

Registration returns the generated secret once with `Cache-Control: no-store`.
Listing, updates, delivery history, and revocation responses never return it.
`POST /webhooks/<webhook_id>/rotate-secret` returns a replacement secret once
with `no-store`; the old secret is replaced immediately. A revoked secret
disables the endpoint until an authorized rotation. Secret values are excluded
from action-audit details.

## Operations and evidence limits

Delivery success/failure and retryable failures are recorded using bounded
webhook metric names. Delivery latency is included in pipeline duration
telemetry and structured completion/failure logs. Each worker tick runs
tenant-scoped webhook health analysis over the preceding 30 minutes, considers
stale `pending`/`processing` rows degraded, and persists/updates a
tenant-scoped `webhook_delivery_failure` discrepancy for escalation rules.
Existing unresolved incidents are updated rather than duplicated each tick.
Terminal failures remain tenant-scoped `WebhookDelivery` records; there is no
separate webhook DLQ. Operators must monitor discrepancy/escalation delivery
and worker exit status, and use an authorized, receiver-coordinated replay
procedure for ambiguous outcomes.

Focused unit and HTTP tests use local databases and mocked outbound transport.
They do not verify production DNS behavior, firewall/egress enforcement,
production proxy settings, real TLS certificates, receiver replay storage, the
least-privilege registry role against deployed PostgreSQL, Redis lock behavior
under concurrent production workers, scheduler alert configuration, or
recovery in a deployed worker. Those remain deployment verification items.
