# PesaGuard Deployment Architecture

## Runtime packaging

The repository includes Docker assets under `pesaguard_backend_pipeline/infra/docker/` and infrastructure templates under `pesaguard_backend_pipeline/infra/`. The Python backend dependencies are managed through a single source of truth, `pesaguard_backend_pipeline/requirements_3.txt` (consumed by both Dockerfiles and all CI workflows; `requirements.txt`, `requirements_1.txt`, `requirements_2.txt` are legacy snapshots retained for reference — do not fork further).

Compose requires loopback-safe deployment settings in the ignored root `.env` (see `pesaguard_backend_pipeline/.env.example`: `POSTGRES_USER`, `POSTGRES_DB`, `POSTGRES_PASSWORD`, `DATABASE_URL_DOCKER`, `REDIS_URL_DOCKER`, `KAFKA_BOOTSTRAP_SERVERS_DOCKER`, `PESAGUARD_BIND_HOST_DOCKER`, `PESAGUARD_API_URL`, `JWT_SECRET_KEY`, plus approved provider identifiers `DEPLOYMENT_REGION` / `BACKUP_REGION` / `LOG_REGION`). Alertmanager and Prometheus bearer material is mounted through Compose secrets (`PESAGUARD_PROMETHEUS_TOKEN_FILE`, `PESAGUARD_ALERTMANAGER_WEBHOOK_SECRET_FILE`), never baked into images.

## Local development

Use the workspace virtual environment or install dependencies from the project requirements file before running the Flask routes or testing the pipeline.

## Container model

The repository ships:

- `pesaguard_backend_pipeline/infra/docker/Dockerfile` — API image (uvicorn + `canonical_app`, non-root `appuser`, Docker-native `/live` healthcheck)
- `pesaguard_backend_pipeline/infra/docker/Dockerfile.worker` — worker image (RQ + communications)
- `pesaguard_backend_pipeline/infra/docker/docker-compose.yml` — full production-shaped stack (postgres, redis, redpanda, api, dashboard, workers, observability)
- `pesaguard_backend_pipeline/infra/docker/docker-compose.full.yml` — Kafka/ZooKeeper variant
- `pesaguard_backend_pipeline/infra/docker/docker-compose.staging.yml` — staging data services
- `pesaguard_backend_pipeline/infra/docker/docker-compose.aws.yml` / `docker-compose.tunnel.yml` — ingress overlays

Service entrypoints use absolute module invocation (`python -m pesaguard_backend_pipeline.<module>`); relative script paths break sibling imports and must not return. The Kafka-only `reconciliation_job` consumer (no HTTP port) emits a file-mtime heartbeat consumed by its Compose healthcheck — a wedged consumer goes unhealthy instead of idling green.

These files map mostly to the infrastructure and deployment model already observed in the repository. The code does not require a full rewrite of the current runtime packaging.

## Environment variables

Production and local runtime settings are driven through environment variables documented in `.env.example` and loaded by modules using `os.getenv(...)` patterns.

## Deployment posture

The project supports staged deployment flows and health checks through `health.py` and route conventions. Operationally, Sentry and Sourcery should remain environment-driven and optional rather than embedded in the code as runtime requirements.

## Release gates

Every staging or production release must follow this order:

1. Run the full CI test and compile gates.
2. Build the immutable container image and scan it for vulnerabilities.
3. Apply the reviewed Alembic migrations using a deployment identity before serving traffic.
4. Deploy the image and wait for `/health` to return JSON with `status: "ok"`.
5. Run authenticated smoke checks for webhook acceptance, tenant-scoped reads, outbox delivery, and metrics.
6. Monitor error rate, latency, database pool usage, consumer lag, retry rate, and dead-letter rate for the release window.
7. Promote only after the health and smoke checks pass.

The staging workflow requires the `STAGING_HEALTH_URL` secret when a deploy hook is configured. A deploy webhook response alone is not evidence that the revision is healthy.

The unified release workflow also publishes `ghcr.io/<owner>/<repository>@<digest>`. Staging deploys from the `staging` branch after verification. Production deploys from `main` behind the protected GitHub `production` environment, or through an explicitly approved manual dispatch. Configure deployment hooks to consume the digest supplied in the request payload rather than a mutable branch tag.

## Migration safety

Migrations must be backward-compatible with the currently running image during rolling deployment. Take or verify a database backup before destructive changes, separate expand/backfill/contract work, and never use application startup `create_all()` as a production migration mechanism.

## Rollback trigger

Rollback when health checks fail, error or latency SLOs regress, tenant isolation alarms fire, or transaction/outbox/DLQ counts move unexpectedly. Preserve the release SHA, health response, migration revision, metrics snapshot, and incident correlation ID before restoring the previous image.
