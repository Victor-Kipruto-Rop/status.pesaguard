# Tenant Isolation and PostgreSQL Security

## Authoritative boundary

The authenticated principal's tenant is authoritative for API requests. JWT
tenant claims are trusted only after signature, issuer, audience, expiry, and
tenant-format validation. API keys resolve their tenant from the stored key
record; callers cannot select a tenant by changing a request field. Public
registration uses the server-configured `PUBLIC_REGISTRATION_TENANT_ID`,
`PESAGUARD_PUBLIC_REGISTRATION_TENANT_ID`, or `TENANT_ID` in that order; a
caller-supplied tenant must match that value. Production registration fails
closed when no tenant is configured. A cross-tenant administrative route must
first authorize the requested tenant, then run its database work in that
tenant's scope.

Single-tenant background jobs must receive a tenant ID from a trusted,
persisted job or event envelope and establish it before opening database
transactions. Reconciliation rejects tenantless Kafka messages from business
processing and durably quarantines them under the reserved
`PESAGUARD_UNATTRIBUTED_TENANT_ID` (default `pesaguard-unattributed`) bucket.
That bucket is operational quarantine, not a customer tenant; it must not be
assigned to customer users. Kafka payloads and headers are not self-authenticating:
production brokers still require TLS, authenticated producers/consumers, and
topic ACLs that prevent untrusted producers from forging tenant identities.
Those broker controls must be verified in the deployed environment.

Unauthenticated login, email-verification, password-reset, and WebAuthn
challenge lookups establish a transaction scope from the requested tenant
before querying tenant-owned rows. This tenant value is a lookup scope, not
proof of identity: login still requires valid credentials, and verification,
reset, or WebAuthn completion still requires the corresponding single-use
secret or cryptographic assertion. WebAuthn challenge responses include the
tenant ID required for the subsequent scoped verification request.

`tenant_context.py` carries the trusted tenant in a `ContextVar`. A SQLAlchemy
`Engine.begin` listener sets PostgreSQL `app.tenant_id` with
`set_config(..., true)`, making it transaction-local. Missing context writes an
empty value, which matches no tenant policy. Commit and rollback discard the
setting before a pooled connection is reused. Establish scope before the first
statement in a transaction; do not switch tenants inside an already-open
transaction.

## PostgreSQL RLS

Migration `20261014_tenant_row_level_security` enables and forces row-level
security on every discovered table containing `tenant_id`. Its `USING` and
`WITH CHECK` policies require the row tenant to equal the transaction-local
GUC. A restrictive guard is added alongside the tenant policy so an older
permissive policy cannot broaden access. Explicit role-scoped exceptions are
limited to the registry role on `iam_tenants` and the API-key lookup role on
`api_key_records`; their grants are separately constrained.
`communication_attempts` now stores the notification tenant, and
communication outbox, attempt, and delivery-report references have composite
tenant-aware foreign keys to the notification. The API-key lifecycle
idempotency table is protected by a policy that derives its tenant through its
referenced API key.

The API-key bootstrap path is a narrow exception to ordinary tenant lookup:
`api_key_records` is protected by RLS, while authentication uses the
`pesaguard_authenticate_api_key(text)` SECURITY DEFINER function. Its owner is a
non-login, non-superuser, non-BYPASSRLS role with access only to the columns
needed to validate one key hash and update `last_used_at`. The application role
gets EXECUTE on that function, not the lookup role's membership or direct
unscoped table access.

The application role sees its own `iam_tenants` row under normal tenant RLS.
Cross-tenant workers enumerate only active tenant IDs through the separate
registry connection. That role receives column-level SELECT on
`iam_tenants.tenant_id` and `status`, no write access, and no business-table
access. `tenant_registry.py` checks that the connected role is not a
superuser/BYPASSRLS role and has no business-table privileges.

The following are intentionally global control-plane or public records and
have no tenant column: global IAM permission/role catalogs, platform catalog,
token revocation IDs, and public status subscriptions. Any future tenant-owned
table must carry a non-empty `tenant_id` or receive an equally explicit
relationship-derived RLS policy in a migration; a missing tenant column is not
an implicit exemption for business data.

### Required database roles and configuration

Pre-provision distinct PostgreSQL roles before applying the RLS migration:

- `PESAGUARD_APPLICATION_DB_ROLE`: the runtime application role. It must be
  non-superuser, must not have `BYPASSRLS` directly or through inherited
  roles, and must not inherit the registry or API-key lookup role.
- `PESAGUARD_TENANT_REGISTRY_DB_ROLE`: the registry-only worker role. It must
  be non-superuser, non-BYPASSRLS, must not inherit privileges from any
  database role, own database objects, create objects in user schemas, or
  execute SECURITY DEFINER functions, and may read only the tenant ID/status
  columns needed for enumeration.
- `PESAGUARD_API_KEY_LOOKUP_DB_ROLE`: a `NOLOGIN`, non-superuser,
  non-BYPASSRLS role with no role memberships that owns the API-key lookup
  function. It must not be granted to the application or registry role.

Set those three role-name variables for Alembic. The migration grants only the
specific policy/table/function privileges described above; it does not create
roles or set passwords. The process that enumerates tenants must also set
`PESAGUARD_TENANT_REGISTRY_DATABASE_URL` to a credential for the registry-only
role. Keep ordinary business queries on the application database URL, and do
not reuse an administrator, migration, superuser, or BYPASSRLS credential in
application processes.

`FORCE ROW LEVEL SECURITY` does not constrain PostgreSQL superusers or roles
with `BYPASSRLS`. Verify the effective runtime role, inherited memberships,
table grants, and enabled/forced policies after deployment; migration success
alone is not deployment evidence.

## Workers, cache, files, and events

Multi-tenant outbox workers enumerate IDs through the registry-only credential,
then claim and update rows under one tenant scope at a time. Scheduled reports
follow the same pattern. Single-tenant reconciliation, RQ replay, and alert
processing must preserve the explicit tenant through each database transaction.
Tenantless reconciliation/alert events are rejected from business processing;
quarantine records use the reserved unattributed bucket rather than the shared
`default` tenant.

Redis cache keys must include tenant and authorization context. Export/report
queries and generated files must be scoped to the authenticated tenant, and
download authorization must be rechecked when retrieving a file rather than
trusting its object key. Webhook handlers must derive tenant from a verified
subscription/provider binding, not from an unsigned caller-supplied tenant ID.

Kafka messages carry tenant identity in the event envelope and a header. The
consumer treats the event tenant as untrusted input until it has passed
validation and the broker's producer ACL boundary. A missing or malformed
tenant is never silently rewritten to a customer tenant.

## Tests and deployment evidence

Unit tests cover nested and exception-safe context restoration, async task
isolation, public-registration tenant spoof rejection, tenantless alert
rejection, and tenantless Kafka quarantine.
`tests/test_postgres_tenant_isolation.py` is an opt-in integration test that
requires the RLS migration and a dedicated PostgreSQL application role with
RLS enabled and no superuser/BYPASSRLS privileges:

```powershell
$env:PESAGUARD_RUN_POSTGRES_INTEGRATION = "1"
$env:PESAGUARD_POSTGRES_TEST_URL = "<dedicated-test-database-url>"
python -m pytest tests/test_postgres_tenant_isolation.py -q
```

The test verifies tenant-bounded reads/writes, missing-context denial, and
pooled-connection context reset. It does not replace deployment checks for
connection-pool configuration, role membership/grants, Kafka ACLs, Redis
partitioning, background scheduler credentials, or export/download paths.
Do not run migration or cleanup tests against a customer database.

Local verification on 2026-10-14 used the named disposable PostgreSQL 16 test
container and local Redis 7 service. The combined authentication, registration,
worker, event, export, investigation, security-audit, reporting, webhook,
ingestion, scheduled-report, dashboard, transaction-search, and PostgreSQL
tenant-isolation regression batch completed with **177 passed**. PostgreSQL
integration ran as a non-superuser, non-BYPASSRLS application role; the
registry-only role also passed the runtime privilege checks and enumerated an
active test tenant. The API-key lookup function was exercised while direct
application-role SELECT on `api_key_records` was revoked.
After the registration request-body and server-tenant validation changes,
`tests/test_registration_hardening.py` completed with **9 passed**.

The reporting/export follow-up added explicit cross-tenant denial checks for
dead-letter listings and caller-selected CSV exports. The route-policy audit
classified **241 route/method entries** with no `UNMAPPED` entries; the focused
authorization, reporting-boundary, and export-scoping suite completed with
**21 passed**. This verifies that routes have an authorization classification
and that these tested export paths reject tenant spoofing; it does not prove
that every route, query, asynchronous path, cache key, or deployed policy is
tenant-safe.

The RLS migration upgraded, downgraded one revision, and upgraded again on the
disposable database. The migration graph resolved to one base and one head
(`20261014_tenant_row_level_security`). Python compilation and scoped
`git diff --check` passed. The test database's regular application-table grants
were provisioned manually for the integration run; this verifies RLS behavior,
not the deployment's application-role provisioning procedure.

These results are local integration evidence, not deployment evidence. The
complete route/query/cache/file-download/webhook audit, production role and
grant verification, deployed connection-pool behavior, Kafka ACLs, and Redis
tenant-key audit remain outstanding. This is not a claim of complete tenant
isolation or production readiness.

Rollback disables the RLS policies and removes the communication tenant
column/FKs that were introduced by this revision; the attempt tenant is
re-derived from its notification during upgrade. A rollback also removes the
API-key lookup function and role grants. Downgrade must be exercised against a
disposable PostgreSQL database before operational use.
