# Webhook Platform

Webhook endpoint management, event subscriptions, signed delivery, durable
attempt history, retries and dead-letter replay are implemented in the developer
platform. Delivery is at-least-once; receivers must deduplicate by event ID.

## What is built

| Component | State |
| --- | --- |
| HMAC signing | **Complete and tested** |
| Timestamp validation | **Complete and tested** |
| Replay protection (server side) | **Complete and tested** |
| Signature versions | **Complete and tested** |
| Retry policy with backoff + jitter | **Complete and tested** |
| Endpoint management and encrypted signing-secret storage | **Implemented** |
| Project-bound event subscriptions and catalog | **Implemented** |
| Scheduled HTTP delivery from committed outbox events | **Implemented** |
| Append-only attempt history, retries, dead-letter and replay | **Implemented** |
| Endpoint editing and secret rotation | Implemented |

## Signing

Every delivery carries a header:

```
PesaGuard-Signature: t=1712345678,v1=4e82adaac59e5124b4db704461ccbe38476325eec69fe913625889eccadd6772
```

`v1` is HMAC-SHA256 over `"{version}.{timestamp}.{body}"`.

### Canonical example

These values are cross-checked against two independent implementations (Python
and Node) on every test run, so the documented example and the implementation
cannot drift apart. An integrator copying them will get a signature that verifies.

```
secret:  whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw
body:    {"id":"evt_01","type":"payment.succeeded","data":{"id":"pay_123","amount":5000,"currency":"KES"}}
time:    1712345678
header:  t=1712345678,v1=4e82adaac59e5124b4db704461ccbe38476325eec69fe913625889eccadd6772
```

The body must be signed **byte for byte as sent**. A trailing newline, a
different key ordering, or a re-serialized payload all produce a different
signature — verify against the raw request body, never a re-encoded copy.

Python, for reference:

```python
signed = f"v1.{epoch_seconds}.{body}"
sig = hmac.new(secret.encode(), signed.encode(), hashlib.sha256).hexdigest()
header = f"t={epoch_seconds},v1={sig}"
```

### Why the timestamp is signed

A signature over the body alone is a bearer token for "this payload is genuine".
An attacker who captures one delivery can replay it forever, and the receiver has
no way to tell. Binding the timestamp means a captured delivery stops verifying
once it falls outside the tolerance window.

### Verification

Two independent checks, both of which must pass:

1. **Freshness** — the timestamp must be within five minutes of now.
2. **Authenticity** — the HMAC must match, compared in constant time.

Drift is compared **absolutely**. A timestamp far in the future is as suspicious as
one far in the past; an "older than" check alone would accept a signature from a
clock hours ahead.
## Retry policy

Exponential backoff with **full jitter**: the delay is drawn uniformly from
`[0, min(cap, base * 2^attempt)]`, not set to exactly `base * 2^attempt`.

### Why jitter is not cosmetic

Without it, every endpoint that failed at the same moment retries at the same
moment. After a PesaGuard incident, every integrator in the country retries in
lockstep, and the retry storm takes down the platforms they are retrying against.
That is a self-inflicted outage caused by the very mechanism meant to help
recovery. Full jitter also stops many deliveries to one endpoint from
synchronising with each other.

The cap bounds the tail. Without it, `2^attempt` eventually exceeds any delay a
client will wait and the delivery is abandoned before its attempt budget is spent.
The shift is capped at 30 so a large attempt number cannot overflow into a
negative duration and schedule a retry in the past.

### Which responses are retried

| Status | Retried | Why |
| --- | --- | --- |
| 2xx | no | Success |
| 408, 429 | **yes** | About timing, not about the request |
| 410 | no | Endpoint is gone; permanent by definition |
| Other 4xx | no | The request itself is wrong; retrying changes nothing |
| 5xx | **yes** | The receiver may recover |

Retrying an identical request that was rejected wastes the attempt budget and
hammers the receiver. Transport failures (DNS, timeout, connection) always retry:
they say nothing about the receiver's intent.

## Endpoint and delivery behavior

- Endpoints are bound to both a project and an environment. List endpoints with
  `GET /api/v1/webhooks/endpoints?projectId={projectId}&environmentId={environmentId}`;
  create one with `POST /api/v1/webhooks/endpoints` and a JSON body containing
  `projectId`, `environmentId`, `name` and `url`. The generated signing secret
  is encrypted at rest, returned once, and never included in endpoint list or
  status responses. Existing endpoints and subscriptions are assigned to Sandbox
  by the environment-isolation migration; create a separate Production endpoint
  and rotate its one-time secret before enabling live delivery.
- Include the owning `environmentId` as a query parameter when changing endpoint
  status, updating configuration, or rotating its secret. A mismatched environment
  is rejected rather than operating on an endpoint in another environment.
- Status changes use
  `PATCH /api/v1/webhooks/endpoints/{endpointId}/status?environmentId={environmentId}`;
- `PATCH /api/v1/webhooks/endpoints/{endpointId}?environmentId={environmentId}`
  updates an endpoint's name and HTTPS destination after re-validating the target.
  Deleted endpoints cannot be edited.
- `POST /api/v1/webhooks/endpoints/{endpointId}/rotate-secret?environmentId={environmentId}` replaces the
  encrypted signing secret and returns the new secret once. The previous secret
  stops signing subsequent deliveries immediately; update the receiver before
  relying on new deliveries.
- Only HTTPS URLs resolving to public addresses are accepted. Validation runs at
  endpoint creation and again immediately before each send. Redirects are not
  followed, and connection and request timeouts are bounded.
- `POST /api/v1/events/subscriptions` binds an active endpoint to a registered
  event type in the same project environment. `projectId`, `environmentId` and
  `endpointId` are required; an endpoint from a different environment is rejected.
- Event, subscription and delivery reads, delivery replay, and webhook-delivery
  CSV exports require both `projectId` and `environmentId`. The API key data
  endpoints derive this scope from the key's bound environment.
- The scheduled delivery worker consumes committed outbox records independently
  of the optional Kafka publisher. Each retry is a new append-only
  `event_deliveries` row. The signed
  envelope carries event ID/type/version and the event payload.
- HTTP 2xx succeeds. 408, 429, 5xx, and transport failures are retried using the
  tested full-jitter retry policy. Other 4xx (including 410) fail permanently.
  Exhausted attempts enter the dead-letter state and can be replayed explicitly.
- Delivery responses are discarded; response bodies, request credentials and
  signing secrets are not written to delivery history or application logs.

Authorized organization members can edit an endpoint or rotate its secret from
the Webhooks page. The one-time secret is shown in the page only after creation
or rotation and cannot be retrieved later.

### SSRF limitation

`OutboundTargetGuard` rejects loopback, private, link-local, multicast and
metadata destinations and validates every DNS answer. Webhook delivery pins the
HTTP client's DNS resolver to those validated addresses for the request, while
the original hostname remains in the URI for TLS SNI, certificate validation
and the HTTP Host header. Redirects remain disabled, so a receiver cannot
redirect the worker to an unchecked destination.

### Remaining limitations

- Signing-secret rotation with an overlap/grace period is not implemented.
- Subscription payload version is pinned to the currently registered event
  version; historical schema transformation is not implemented.
- Endpoint ownership verification/challenge handshake is not implemented.
- Delivery execution has not yet been verified against a production receiver or
  a multi-instance PostgreSQL deployment.

## Verification status

- The signing and retry policy are covered by **43 passing tests**, including a
  cross-implementation check.
- **Cross-validation is performed and passing.** `WebhookSignatureTest` executes
  two independent reference implementations
  (`src/test/resources/webhook_signature_reference.py` and `.js`), both written
  from the documented rule rather than ported from the Java source, and asserts
  that all three agree on the canonical vector *and* on verification outcomes for
  fresh, stale and forged headers. It skips only if an interpreter is absent, so a
  build that ran it is distinguishable from one that did not.
- The V34 webhook endpoint migration and scheduler still require integration
  validation against PostgreSQL and a controlled HTTP receiver.
- **Uncommitted** — the working directory is entirely untracked and CI has never
  run. In CI the cross-check will only run if Python or Node is installed on the
  runner; if neither is present the test skips, so the pinned canonical vector
  remains the durable guarantee.

Comparison is constant-time. `String.equals` short-circuits on the first differing
byte, which leaks how much of a guess was correct and turns brute force from a
2^128 search into a byte-at-a-time oracle.

### Honest limitation: replay within the window

A replayed delivery **inside** the five-minute window still verifies. Timestamp
validation alone cannot detect it, and pretending otherwise would be a false
assurance. Full protection requires the receiver to dedupe on the delivery id,
which is why the delivery id must be sent as a separate header. This is a
receiver-side responsibility and must be documented to integrators.
