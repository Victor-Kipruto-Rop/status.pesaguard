# Security Policy and Production Checklist

PesaGuard processes sensitive financial transaction, webhook, reconciliation, and customer data. This document defines the reporting process and the controls that must be verified before a production release. It is a release aid, not proof that a control is implemented or operating.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability. Use the repository's [private security advisory](https://github.com/Victor-Kipruto-Rop/pesaguard/security/advisories/new) or contact the maintainer through their [GitHub profile](https://github.com/Victor-Kipruto-Rop).

Include:

- the affected component and observed impact;
- reproduction steps and minimal evidence, with sensitive data redacted;
- relevant versions, logs, and configuration state;
- a proposed mitigation, if known.

The project aims to acknowledge a complete report within 48 hours and provide a remediation timeline within five business days. These are response targets, not a service-level guarantee.

## Scope

In scope:

- the PesaGuard application, API, webhook receiver, workers, and dashboard;
- infrastructure configuration under the project's control;
- authentication, authorization, tenant isolation, transaction integrity, secrets, and audit controls.

Out of scope:

- vulnerabilities in Safaricom Daraja or another third-party service;
- attacks against infrastructure that the project does not operate;
- denial-of-service testing without prior written authorization.

## Release checklist

A release owner must record evidence for each item. An unchecked or unverified item is a blocker.

### Authentication and authorization

- [ ] Production startup fails closed when JWT signing material is missing or invalid.
- [ ] Authentication cannot be disabled through production environment settings.
- [ ] Protected routes enforce identity, role/permission, tenant, resource ownership, and action checks server-side.
- [ ] Role and permission mappings match an approved authority matrix.
- [ ] Token expiry, refresh, logout, revocation, and account-disable behavior are covered by automated and manual tests.

### Webhooks and input handling

- [ ] Provider callbacks are treated as untrusted input.
- [ ] Signature or shared-secret verification uses a constant-time comparison.
- [ ] Source restrictions are configured where the provider contract supports them.
- [ ] Unrestricted webhook-source overrides are absent from production configuration and rejected by production policy.
- [ ] Request-size limits, schema validation, timestamp/replay controls, and provider identity checks are verified.
- [ ] Duplicate and out-of-order callbacks do not duplicate financial effects.

### Tenant and data isolation

- [ ] Every tenant-owned query, mutation, export, event, cache entry, notification, and background job includes trusted tenant context.
- [ ] User-supplied tenant identifiers are authorized before use.
- [ ] Database constraints prevent empty tenant identifiers and cross-scope uniqueness violations.
- [ ] Cross-tenant negative tests pass on PostgreSQL, not only SQLite.
- [ ] Sensitive financial and authentication values are excluded from logs, metrics, traces, and error responses.

### Secrets and cryptography

- [ ] Secrets are supplied by the deployment's approved secret store or protected environment mechanism, not committed files.
- [ ] Rotation cadence and owners are approved rather than left as placeholders.
- [ ] Key-version overlap, revocation, rollback, and audit evidence have been tested.
- [ ] Field encryption and key rollover behavior are verified for provider configuration and sensitive payloads.
- [ ] TLS termination and internal service transport are verified in the deployed topology.
- [ ] Any credential ever committed, logged, or otherwise exposed is treated as compromised and rotated after an exposure review.

See [Secrets management](SECRETS_MANAGEMENT.md) for the current rotation procedure. Its cadence values remain operational placeholders until approved by the security owner.

### Data integrity and financial correctness

- [ ] Financial writes are atomic, idempotent, validated, and auditable.
- [ ] Amounts use approved decimal precision; binary floating point is not used for persisted financial calculations.
- [ ] Failed or invalid events are rejected, retried, quarantined, or dead-lettered without silent loss.
- [ ] Migrations preserve existing data and constraints and are tested against a production-shaped PostgreSQL dataset.
- [ ] Reconciliation ground truth is approved by a named finance/data owner and meets the approved accuracy threshold.

### Reliability and operations

- [ ] Bounded retry and backoff behavior is tested for each external dependency.
- [ ] Duplicate delivery, consumer restart, poison events, out-of-order delivery, and dead-letter replay are exercised on the real event platform.
- [ ] Database, Redis, broker, and provider failure drills meet the approved recovery objectives.
- [ ] Encrypted off-site backup delivery is mandatory in production.
- [ ] A base-backup and WAL/PITR restore has been completed into an isolated environment with measured RPO and RTO.
- [ ] Alert rules, routing, ownership, runbooks, and delivery have been tested.
- [ ] Deployment and rollback procedures are tested against the release artifact.

### Supply chain and CI

- [ ] Python and frontend dependencies are installed from locked, reviewed dependency definitions.
- [ ] Static analysis, secret scanning, dependency auditing, and container scanning run as blocking release gates.
- [ ] Unit, PostgreSQL/Redis/Kafka integration, security, failure-recovery, and relevant end-to-end suites pass on the exact release revision.
- [ ] CI does not suppress security or production-critical test failures with `continue-on-error`.
- [ ] The release image is immutable, scanned, traceable to a clean reviewed commit, and deployed only after verification.

## Disclosure

Reporters should give the project a reasonable opportunity to investigate and release a fix before public disclosure. The project may credit a reporter only with permission.

## Contact

Victor Kipruto Rop — [GitHub](https://github.com/Victor-Kipruto-Rop)