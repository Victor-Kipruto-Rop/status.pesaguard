# Security Audit

This is a defensive, source/configuration review. No external probing or credential
testing was performed. No exploitable application vulnerability was reproduced;
the test suite could not run, so absence of a finding is not a security clearance.

## Source-level controls observed

- `auth_rbac.py` requires a JWT signing secret at startup with a minimum 32-byte
  value, validates issuer/audience and key IDs, and limits access token TTL to
  1–15 minutes. The module describes persisted refresh rotation/revocation state.
- `api/dashboard_app.py` applies auth and permission decorators, a global request
  authentication path, rate limiting, request-size checks, security headers, and
  tenant checks. SQLAlchemy loader criteria apply tenant scoping to ORM queries
  when a tenant user is active.
- `security_helpers.py` uses `X-Forwarded-For` only when
  `PESAGUARD_TRUSTED_PROXY_COUNT` is explicitly positive; otherwise it uses the
  direct peer address. Webhook source checks fail closed when no secret or IP
  allowlist is configured (except an explicit development override).
- `models.py` contains tenant-scoped uniqueness, nonempty tenant checks, positive
  amount checks, and `Numeric(18,2)` money fields.
- The inspected source tree had no `ENABLE ROW LEVEL SECURITY` or `CREATE POLICY`
  directives in Python/SQL files. Tenant separation is therefore application and
  constraint-based in the inspected implementation, not verified PostgreSQL RLS.

These are source observations only. They do not establish runtime configuration,
completeness of route authorization, safe logging, or a successful cross-tenant
test run.

## Deployment risks and gaps

1. **HIGH — publicly published internal services:** the merged AWS Compose config
   retains ports for the API, webhook receiver, database, cache, broker and
   observability services on all host interfaces. This bypasses the intended Nginx
   ingress boundary. Apply least-privilege port bindings and verify the host/cloud
   firewall before any public deployment.
2. **MEDIUM — CORS origin mismatch risk:** default origins include
   `https://app.pesaguard.co.ke` and status/docs/public origins, but not the previously
   specified `https://dashboard.pesaguard.victorkipruto.com`. Production
   `PESAGUARD_CORS_ALLOWED_ORIGINS` was not available; validate it against the
   actual deployed dashboard origin.
3. **MEDIUM — proxy configuration unverified:** forwarded IP interpretation is
   guarded by a trusted-proxy count, but the correct value for the eventual
   Cloudflare/host-Nginx/tunnel chain is unknown. Wrong values can break source IP
   allowlists or trust attacker-controlled forwarding headers.
4. **BLOCKED — runtime secret, key, and tenant-isolation checks:** no production
   secrets were read, no live auth flow was exercised, and cross-tenant tests were
   not executed. Keep the values confidential and provide a sanitized deployment
   configuration review.
5. **BLOCKED — vulnerability/dependency scan result:** CI defines image scanning
   and SBOM generation, but no successful run or current dependency report was
   obtained during this audit.

## Required safe verification

After the test harness is isolated, run tests for revoked/expired JWT/API keys,
role/permission denials, cross-tenant list/read/write paths, webhook HMAC failures,
replay/idempotency, proxy header spoofing, request-size limits, and log/trace
redaction using synthetic tenants and sandbox endpoints only. Confirm every
route-level exception (including internal developer-key routes) has its own
authentication control before exposure.

