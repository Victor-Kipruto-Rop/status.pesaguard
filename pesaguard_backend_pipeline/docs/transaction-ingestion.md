# M-Pesa transaction ingestion

PesaGuard accepts supported M-Pesa C2B confirmations and successful STK callbacks
through the Daraja webhook boundary. Ingestion records transactions for
reconciliation; it does not initiate or confirm payments, execute refunds, or
move funds.

## Callback security and contract

`POST /webhook/mpesa/confirmation` and `POST /webhook/mpesa/validation` require:

- A JSON object within `PESAGUARD_WEBHOOK_MAX_BODY_BYTES`.
- A request source authorized by `DARAJA_SHARED_SECRET`, `DARAJA_ALLOWED_IPS`,
  or both. If both are configured, both checks apply.
- `X-Daraja-Signature`, an HMAC-SHA256 signature over the exact raw request
  bytes, encoded as the hex or base64 form accepted by the shared Daraja
  signature validator.
- `DARAJA_WEBHOOK_SIGNING_SECRET`, held separately from the Daraja OAuth
  consumer secret. A missing key fails closed with HTTP 503; a missing or
  invalid signature is rejected with HTTP 403.

Use a secret manager for `DARAJA_WEBHOOK_SIGNING_SECRET`; do not reuse
`DARAJA_CONSUMER_SECRET`. The deployment must arrange for the callback sender or
trusted ingress to add this HMAC header. `TENANT_ID` is the trusted tenant
context for this single-tenant webhook gateway and must be configured.
The `/ready` check reports the service as degraded until the signing key,
source-authentication gate, and tenant context are configured; secret values
are never included in the health response.

Source schema version `1.0` is currently supported. Unsupported or malformed
transactions are stored in the tenant-scoped dead-letter table before a
permanent HTTP 400 response is returned. If dead-letter persistence is
unavailable, the gateway returns HTTP 503 so the sender can retry. The
signature is checked before JSON parsing or transaction persistence.

Successful STK callback metadata must include `Amount`, `MpesaReceiptNumber`,
`TransactionDate`, and `PhoneNumber`. The callback must contain a
`BusinessShortCode`; where Daraja omits it, configure the trusted
`DARAJA_BUSINESS_SHORT_CODE` mapping. An unmapped STK callback is rejected and
dead-lettered rather than being assigned to the legacy default account.

## Monetary amount contract

`normalization.py` is the shared amount contract used by provider adapters,
HTTP validation, batch and real-time ingestion, scoring, reconciliation,
aggregation, settlement calculations, and CSV/Excel purchase-order and ledger
imports. Supported currencies are explicitly limited to KES, TZS, UGX, USD,
EUR, GBP, and BHD, with 2, 2, 0, 2, 2, 2, and 3 minor-unit digits respectively.
This allowlist does not imply support for additional payment rails; Daraja
remains the active M-Pesa provider scope.

Amounts are positive finite decimal values, bounded by the 16-integer-digit,
3-fractional-digit `NUMERIC(19,3)` storage contract. Numeric strings, JSON
numbers, and scientific notation normalize identically when they represent an
exact valid amount. NaN, positive/negative infinity, malformed values, zero or
negative transaction amounts, values below one minor unit, unsupported
currencies, excess decimal precision, and out-of-range values are rejected.
Provider values with excess precision are never rounded. `ROUND_HALF_UP` is
reserved for explicitly derived values such as averages and settlement totals.

The normalized amount is stored in canonical fixed-scale form (for example,
`1.00` KES, `123` UGX, or `1.234` BHD). The unmodified source payload remains
separate and immutable for audit/dispute investigation; normalization must not
rewrite the provider's original numeric representation or currency spelling.
Comparisons, baselines, settlement calculations, and aggregate buckets are
currency-scoped. PesaGuard does not perform implicit FX conversion; a
cross-currency reconciliation candidate is an explicit exception. Aggregations
over a non-currency dimension retain `(dimension, currency)` as their key.
Notification thresholds are interpreted only in their configured currency;
a mismatched currency is not compared, and reconciliation escalation is
conservatively routed for critical review.

## Idempotency, persistence, and status

Transactions are idempotent within a tenant/provider scope using the provider
transaction reference; the provider account is also retained and constrained
for webhook-attempt tracking. An identical retry is acknowledged as a duplicate
and increments its persisted webhook-attempt count. Reuse of a transaction
reference with different financial content is a conflict (HTTP 409), not a
duplicate. The transaction, processed-event status, idempotency record, audit
event, and downstream outbox intent are committed together.

The raw source payload is stored with a SHA-256 digest and source schema version.
Application writes and a database trigger prohibit mutation of source fields.
Authorized privacy workflows may replace the raw payload with an explicit
redaction record; lifecycle/status changes remain separate from source data.
Raw content is protected using the configured payload-encryption controls.

`GET /api/v1/transactions/{transaction_id}/status` returns the transaction
status and an `ingestion` object containing its stored/failed status, callback
attempt count, source schema version, and whether an ingestion error is present.
Detailed validation failures are retained in the tenant-scoped dead-letter
record rather than returned through the transaction status API.

## Retries, dead letters, backfill, and replay

Transient database failures return a retryable HTTP 500. The durable transaction
outbox retries downstream publication using its existing bounded retry and
dead-letter behavior. A callback rejected by validation is dead-lettered and
can be replayed by an authorized operator after correcting the underlying
mapping or contract issue. Replay re-runs source validation and idempotent
transaction persistence; it does not bypass the transaction store or create a
second financial record.

Historical imports use the tenant-scoped `POST /api/v1/imports` and
`GET /api/v1/imports/{import_id}` endpoints. Import files are bounded by
`PESAGUARD_IMPORT_MAX_BYTES` and `PESAGUARD_IMPORT_MAX_RECORDS`; supported
formats are CSV, JSON, XLSX, and Parquet when their optional parser dependency
is installed. Jobs are resumable by safe idempotent reprocessing, report
per-attempt counters and bounded errors, and use an expiring worker lease to
prevent concurrent processing. Configure `PESAGUARD_IMPORT_OBJECT_STORAGE` on
a durable, access-controlled volume shared by API and workers. The included
filesystem adapter is not an object-store HA guarantee.

## Deployment and recovery

1. Apply `20261013_harden_transaction_ingestion`, then
   `20261013_currency_minor_units`, before deploying code that reads the new
   provenance columns or persists currency-specific precision and metric keys.
   The currency migration widens monetary columns and scopes metric/settlement
   identities by currency. Its downgrade refuses to drop non-KES metric
   denominations, collapse currency-distinct metric buckets, or truncate
   values with more than two decimal places.
2. Configure the signing secret, source gate, tenant, payload encryption, and
   (when needed) STK business-short-code mapping in the deployment secret
   manager.
3. Ensure the worker queue and scheduled transaction-outbox drain are running;
   monitor outbox lag, failed import jobs, and dead-letter backlog.
4. Verify signed test callbacks, replay behavior, a historical import, and
   downstream delivery in an approved non-production Daraja environment before
   enabling production traffic.

Rollback should first revert application code while retaining the additive
migrations. Downgrade only after an approved backup and recovery decision; the
currency migration has explicit data-loss guards and may refuse downgrade when
non-KES records or higher-precision values exist. A local test or green
migration is not evidence that the production callback sender, PostgreSQL
migration, queue, or object-storage deployment has been verified.
