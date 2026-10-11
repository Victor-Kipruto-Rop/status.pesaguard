# Repository Inventory

## Scope and evidence

Repository root: `C:\Users\kipru\pesaguard`; backend: `pesaguard_backend_pipeline`.
Audit revision: `main@8c50cad38e5176aec2513a6ef4584493364a1d42`. At audit start the
backend subtree had extensive modified and untracked work (95 modified, 123
untracked paths per the recorded inventory); the larger repository was also dirty.
No changes from this audit were made outside this new `audit/` directory.

The backend inventory contained 404 Python files, 147 test files, and 56 Alembic
revision files. These are file counts, not test coverage or service-completion claims.

## Runtime and application entry points

- `canonical_app.py` starts the FastAPI control plane via Uvicorn.
- `fastapi_app.py` exposes `/health`, `/live`, `/ready`, and `/version`, and mounts
  the Flask dashboard API and legacy auth app.
- `api/dashboard_app.py` initializes the dashboard/API routes, authentication
  middleware, tenant scoping, CORS, rate limiting, and health endpoints.
- `app.py` is a separate Flask webhook receiver; its default port is 5000.
- `reconciliation_job.py` consumes and processes reconciliation events.
- `worker_entrypoint.sh` starts the RQ worker.
- `communications/worker.py` runs the notification/event worker.

## Data and dependency components

- PostgreSQL 16 is the primary database; SQLAlchemy models are in `models.py`,
  connections/pooling in `database_service.py`, and migrations in `alembic/`.
- Redis 7 is configured for RQ.
- Redpanda is configured as a Kafka-compatible single node.
- Transaction, audit, and communications outboxes and dead-letter entities exist
  in source and migrations.
- External integrations found include Safaricom Daraja, HTTP connectors, email,
  and Africa's Talking SMS. Production credentials and delivery were not inspected
  or exercised.

## Deployment and operations

- Main Compose files: `infra/docker/docker-compose.yml`,
  `docker-compose.aws.yml`, plus other overlays.
- Images: `infra/docker/Dockerfile` and `Dockerfile.worker`.
- Proxy: `infra/docker/nginx.conf`.
- CI/release: root `.github/workflows/backend-release.yml`; it defines database/
  Redis verification, image scanning/SBOM generation, and Render staging-to-production
  deployment steps. Configuration is not evidence of a successful run.
- Backup/restore: `backup_postgres.py`, `operations/restore_drill.py`, systemd
  service/timer units, and deployment runbooks.
- Residency policy: `docs/product/DATA_RESIDENCY.md`.

## Not established

This inventory does not prove the full route contract, all integration semantics,
runtime behavior, active deployment state, external service configuration, code
quality, or test coverage. The pytest suite could not be collected. No duplicate
implementation was adjudicated beyond the demonstrated sibling-checkout import
collision.

