# PesaGuard Current Architecture

## 1. Existing Architecture

PesaGuard is a Flask and SQLAlchemy backend deployed as separate processes: an M-Pesa webhook receiver, dashboard API, Kafka reconciliation consumer, and RQ worker. The active product scope is M-Pesa/Daraja reconciliation and anomaly detection.

## 2. Existing Communication Functionality

- Africa's Talking SMS client: `pesaguard_backend_pipeline/africas_talking.py`
- Slack webhook alerts and SMTP email alerts: `pesaguard_backend_pipeline/notifier.py`
- Localized alert templates: `alerting/templates/`
- Escalation and on-call services: `escalation_engine.py`, `on_call_service.py`
- Existing outbound webhook delivery manager: `webhook_manager.py`
- Provider-neutral communications contracts, tenant-scoped notification and attempt records, a transactional outbox, retry/dead-letter handling, and authenticated delivery webhooks live under `communications/`.
- Email and SMS use the shared notification lifecycle. Email provider configuration is injected; SMS uses the Africa's Talking adapter. Push is supported only through an explicitly injected provider and is not enabled by default. Push recipient tokens are encrypted at rest with the shared payload-encryption key and decrypted only for delivery; production must configure that key.
- Tenant-scoped preferences, consent, approved versioned templates, quiet-hours checks, and communication reporting APIs are present. Provider delivery and production infrastructure still require deployment-specific configuration and verification.

## 3. Existing Integrations

- Active: Safaricom Daraja/M-Pesa callbacks, OAuth, validation, idempotency, reconciliation, and anomaly rules.
- Generic provider configuration and encrypted credentials exist in `provider_management_service.py`.
- Airtel Money, bank transaction adapters, Africa's Talking USSD, voice, WhatsApp, airtime, and data integrations are not implemented.

## 4. Event Infrastructure

- Kafka (`kafka-python-ng`) carries M-Pesa raw, matched, discrepancy, dead-letter, and audit topics.
- Redis carries RQ jobs, rate limits, and short-lived/cache state.
- `producer.py` provides Kafka publishing with acknowledgements, retries, circuit breaking, keyed partitioning, trace/tenant headers, and dead-letter persistence; `event_consumer.py` plus `event_bus.py` validate contracts and provide retry, lag/backpressure, replay, and dead-letter controls. These existing modules are the Kafka/Redpanda producer and consumer boundary; they are not replaced with another abstraction.
- RQ workers handle asynchronous transaction publishing, scheduled reports, cleanup, and failures.
- A channel-aware communication outbox worker is defined in `communications/worker.py`; Kafka communication topics remain separate from the database outbox and are not implied by this worker.

## 5. Database and Data Infrastructure

- `database_service.py` is the shared SQLAlchemy engine/session/pool factory used by the dashboard, authentication/revocation, event store, health probes, and retention job. PostgreSQL pools use validated settings and pre-ping; SQLite uses a static in-memory pool or file-backed no-pool configuration. Query instrumentation is attached when available.
- `migration_service.py` runs the checked-in Alembic revisions explicitly; `init_db.py` uses it. Production schema changes remain migration-owned—runtime `create_all()` is not a migration strategy. Deployments should serialize migration runners and use Alembic downgrade revisions only when the migration is explicitly reversible.
- `redis_service.py` is the validated, bounded-timeout Redis connection boundary used by health checks. Feature-specific components retain their purpose-built Redis operations and failure policy; Redis availability does not imply that process-local locks, caches, metrics, or consumer state are distributed.
- `schema_validation.py`, `schema_compatibility.py`, and `schema_evolution.py` provide payload validation, backward-compatibility checks, and version metadata. They do not automatically transform incompatible payloads.
- `retention_cleanup.py` deletes eligible data in bounded batches, respects tenant filters and legal holds, preserves append-only audit history, and only removes transactions already marked archived. Retention windows and batch size are validated.
- `archival.py` stores hash-partitioned, atomically written local archive objects and can verify manifest hashes on read. The local object store is a development adapter; production requires a durable, access-controlled object-store adapter before relying on archives for deletion.
- `backup_postgres.py` verifies manifests, tests compressed dump integrity, supports encryption/off-site command hooks, and records backup status. `operations/restore_drill.py` and `operations/validate_restore.py` support isolated restore verification. Live PostgreSQL, WAL/PITR, off-site storage, and recovery-time objectives still require deployment-backed drills.
- Database pool metrics, Redis/DB health probes, producer/consumer telemetry, retention summaries, and backup status records are present. Tests with SQLite and fakes do not certify PostgreSQL, Redis, or Kafka/Redpanda behavior in production.

## 6. Database Structure

Production uses PostgreSQL; tests and local paths also use SQLite. SQLAlchemy models are in `pesaguard_backend_pipeline/models.py` and audit models in `action_audit.py`. Alembic migrations exist for transactions, webhooks, providers, auth state, audit integrity, outbox delivery, retention, and encrypted configuration.

Existing relevant tables include transactions, processed transactions, discrepancies, webhook configurations/deliveries, email notifications, dead letters, users/tenants, payment providers, and audit/outbox records. Some runtime paths still call `Base.metadata.create_all()`, which should remain a test/bootstrap concern rather than a production migration mechanism.

## 7. Frontend Structure

No implemented React, Vue, Next.js, or other frontend application is checked in. Frontend references in compose, CI, and documentation point to missing artifacts. The communications dashboard therefore requires a later frontend foundation after backend APIs and design-system decisions are established.

## 8. Authentication and Authorization

JWT authentication supports issuer/audience validation, key IDs and rotation, revocation, sessions, authorization versions, and tenant claims in `auth_rbac.py`. RBAC and tenant checks are present across dashboard routes. Role/permission definitions are duplicated between `role_models.py` and dashboard models, creating authorization drift risk. Some legacy routes still use an admin token rather than the JWT path.

## 9. Observability

Structured logging, correlation/request IDs, health checks, metrics payloads, Grafana configuration, Kafka circuit breaking, RQ failure callbacks, dead-letter persistence, and cryptographic audit logging are present. Communication attempts record provider outcome, errors, latency, and estimated cost; the worker reports repeated provider degradation and queue SLA incidents. These repository controls do not substitute for deployment-level dashboards, alerts, or live provider verification.

## 10. Platform Infrastructure Services

- `runtime_config.py` centralizes validated runtime settings for the webhook and dashboard APIs, including public URL, payload limits, rate limits, tenant/topic names, and port.
- `feature_flags.py` provides tenant-scoped flags. Explicit environment values take precedence as operator kill switches, followed by process overrides, tenant overrides, and defaults. The current JSON-backed settings store synchronizes writes per store instance and atomically replaces its file; multi-worker or multi-instance production writes should use a shared transactional store.
- `health.py` supplies sanitized dependency health data. Flask and FastAPI expose `/health`, `/live`, and `/ready`; liveness avoids downstream probes. Both APIs also expose a non-cacheable `/version` response.
- `request_context.py` validates and bounds request/correlation IDs and trace context. Tenant context is accepted only from trusted server configuration, not an arbitrary tenant header.
- `rate_limiter.py` uses atomic Redis token buckets when configured and a bounded in-process fallback where policy allows. Invalid non-positive limits and token/window values are rejected.
- `distributed_lock.py` provides Redis ownership-token locks with expiry and conditional release/extension; production requires Redis. The in-process fallback is suitable only for a single process.
- `cache_service.py` is a tenant-keyed JSON cache with TTL, bounded LRU process-local fallback, and cache outcome counters. It is advisory state only and is not wired as an authority for financial or authorization decisions.
- `metrics.py`, `observability.py`, `otel_tracing.py`, and `logging_utils.py` remain the shared metrics, trace, and structured logging foundations; these services were reused rather than duplicated.
- Redis, Kafka, OpenTelemetry exporters, and deployment health behavior still require environment-specific integration and live deployment verification. Repository tests do not certify those external systems.

## 11. Reusable Components

The communications implementation should extend, not replace:

- `africas_talking.py` for the provider transport boundary
- `notifier.py` and alert templates for compatibility during migration
- `background_tasks.py`, Redis, and RQ for asynchronous work
- `producer.py` and `topics.py` for Kafka integration
- SQLAlchemy/Alembic for persistence
- `auth_rbac.py`, tenant checks, rate limiting, and `action_audit.py`
- existing health, metrics, logging, Docker, and CI conventions

## 12. Communication Service Boundaries

- `NotificationService` owns validation, tenant scoping, payload-aware idempotency, preference checks, approved-template rendering, persistence, and outbox enqueueing.
- `EmailService` and `SmsService` use the shared lifecycle; `PushNotificationService` requires an injected push gateway. No channel is reported as delivered without an actual provider response.
- `NotificationPreferenceService` validates tenant-recipient settings, supported channels, quiet hours, and IANA time zones. Marketing delivery additionally requires explicit preference opt-in and channel consent.
- `NotificationTemplateService` requires tenant ownership and template approval for rendering. Templates are versioned; creating a new version does not mutate prior notifications.
- Provider delivery is asynchronous through the outbox worker for queued routes. The worker must receive a channel-aware provider factory to deliver channels other than its legacy SMS default.

## 13. Potential Conflicts

- Direct provider calls in legacy alerting can bypass the new orchestrator.
- Kafka topic configuration is currently M-Pesa-specific.
- Docker compose variants use different Kafka distributions/configuration.
- Environment variables are read directly by many modules and are not centrally normalized.
- API versioning is inconsistent: documentation requires `/v1`, while several dashboard routes remain unversioned.
- Frontend and some CI/Docker references are incomplete.
- Existing product documentation limits the active payment scope to M-Pesa, so Airtel/bank expansion must not be implied by the communications work.

## 14. Recommended Implementation Sequence

1. Verify and apply the communications schema migrations in the target deployment.
2. Configure production email/SMS providers and supply a channel-aware factory for every enabled delivery channel.
3. Integrate legacy alert senders with the shared notification lifecycle where bypasses remain.
4. Validate provider callback signatures, retries, outbox lease recovery, tenant authorization, and delivery reporting against the production database and provider sandbox.
5. Build the communications dashboard only after the API contract is stable.
6. Treat push, USSD, voice, WhatsApp, and other gateway channels as unavailable until their real provider, credentials, operational monitoring, and failure recovery are verified.

## 15. API Governance

- `api_governance.py` centralizes URL-major version validation, contract metadata, deprecation headers, compatibility comparisons, OpenAPI validation, and RFC 9457 error enrichment. It reuses `contract_versioning.py` and `schema_compatibility.py`.
- `/api/v1` and `/v1` paths are governed by the supported API policy and return `X-API-Version`; legacy unversioned routes remain available and unchanged. Unsupported explicit URL versions return a structured 400 response.
- Deprecation headers are emitted only for versions explicitly listed as deprecated. A `Sunset` header requires a timezone-aware timestamp; no retirement date is invented. No API version is currently deprecated.
- The dashboard runtime OpenAPI document is loaded from and validated against [the authoritative OpenAPI contract](../api/openapi.json) before adding its dashboard-only paths. Internal references, operation responses, operation IDs, and the contract version are checked.
- JSON API error responses use `application/problem+json` fields while retaining existing `error`, `message`, and request-ID fields. Webhook callback `ResultCode` payloads retain their provider-facing format.
- The compatibility service delegates comparison to `schema_compatibility.compare_openapi`; breaking changes are detectable but do not automatically block runtime requests. CI/release enforcement remains a deployment integration responsibility.
