# Service and Dependency Matrix

| Service | Required for full-stack path | Configured dependency/state | VPS compatibility | Key issue / verification |
|---|---|---|---|---|
| Docker Nginx | Public API ingress | Routes `/webhook/` to receiver; remaining paths to API; 8080 published by AWS overlay on loopback | Can share host | TLS is outside this container; verify host proxy and origin policy. |
| Webhook receiver | Payment callback/ingestion | Flask `app.py`, default port 5000; DB and Kafka; Compose publishes 5000 | Fits as container | Inherited image health check probes default 5001; direct host port remains exposed. |
| Dashboard API | Tenant/dashboard/API requests | FastAPI/Uvicorn 5001 with Flask WSGI apps; PostgreSQL | Fits as container | Direct host port remains exposed; public CORS and deployed origin unverified. |
| PostgreSQL 16 | Durable business state | `pgdata` and `wal_archive` named volumes; port 5433 published | Fits technically; capacity unknown | WAL archive is same-host; direct DB publication remains after AWS overlay. |
| Redis 7 | RQ queue/cache and shared rate limiting | `REDIS_URL_DOCKER`; no explicit data volume | Fits technically | Queue durability on container recreation and persistence policy are unverified. |
| Redpanda | Transaction/reconciliation/notification events | One node, `--smp=1`, 1 GiB memory setting; data volume | Fits only with capacity tradeoffs | Single broker is an availability point; broker/admin ports remain published. |
| Reconciliation job | Event processing | PostgreSQL + Kafka/Redpanda; manual commits | Fits technically | Integration, restart and offset recovery are unverified. |
| RQ worker | Background jobs | Redis, PostgreSQL, Kafka; configured retry policy | Fits technically | Redis persistence and job idempotency under restart need tests. |
| Communications worker | Notifications | PostgreSQL + Kafka | Fits technically | Email/SMS provider behavior and retries not live-verified. |
| Prometheus | Metrics | Config/alerts volume mounts; port 9090 | Fits technically | Published publicly by merged config; no data volume shown. |
| Alertmanager | Alert routing | Config volume mount; port 9093 | Fits technically | Published publicly; delivery path/credentials not verified. |
| Grafana | Dashboards | Depends on Prometheus; port 3000 | Fits technically | Published publicly; no explicit persistence volume shown. |
| OTEL collector | Telemetry receiver/export | Config mount; ports 4317/4318/8888/8889 | Fits technically | Ports published publicly; telemetry destination and privacy not verified. |
| Host backup service/timer | PostgreSQL backup | systemd units require deployment environment; production encryption/upload commands | Host-side operation | No installed/running timer or successful restore evidence. |
| Scheduled reports timer | Reports | Unit references `/home/pesaguard/Desktop/pesaguard` | Host-side operation | Deployment path is desktop-specific; review before Linux installation. |

“Fits technically” means the repository has a container/service definition. It does
not mean the service was built, started, correctly configured, or production-tested.

