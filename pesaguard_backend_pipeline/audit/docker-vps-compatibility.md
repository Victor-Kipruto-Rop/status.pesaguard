# Docker and VPS Compatibility

## Effective network exposure

The Compose base file publishes these ports without an explicit host IP:

| Service | Host → container |
|---|---|
| Webhook receiver | 5000 → 5000 |
| Dashboard API | 5001 → 5001 |
| PostgreSQL | 5433 → 5432 |
| Redis | 6379 → 6379 |
| Redpanda | 19092, 8082, 9644 |
| Prometheus / Alertmanager / Grafana | 9090 / 9093 / 3000 |
| OTEL collector | 4317 / 4318 / 8888 / 8889 |

`docker compose -f docker-compose.yml -f docker-compose.aws.yml config
--no-interpolate --no-env-resolution --format json` successfully parsed the YAML
and showed these mappings still present. `ports: []` in the AWS overlay did not
remove base mappings. Only Nginx's added 8080 mapping binds `127.0.0.1`. This is
direct evidence of a material deployment exposure; see VPS-001.

## Additional compatibility findings

- The image is based on `python:3.12-slim`, installs `requirements_3.txt`, and runs
  as `appuser`. The Dockerfile health check uses `/health` on `$PORT`, default 5001.
- Compose starts the webhook with `python pesaguard_backend_pipeline/app.py`.
  `app.py` defaults to port 5000 and Compose does not set `PORT` for this service.
  Its inherited image check therefore probes the wrong port.
- Compose has persistent named volumes for PostgreSQL data, local WAL archives,
  and Redpanda. Redis, Prometheus, Grafana, and OTEL do not have explicit data
  volumes in the base file.
- The base config pins Postgres 16, Redis 7 and Redpanda `v25.2.2`, but Prometheus,
  Grafana, and OTEL use mutable `latest` tags.
- No explicit Compose restart policies or container resource limits were found in
  the reviewed service definitions. Redpanda has its own 1 GiB startup memory
  argument; that is not a host-wide capacity limit.
- Dependencies use `depends_on`; healthy readiness ordering is not established by
  this alone.
- The Docker Compose AWS overlay does not provide full external HTTPS termination;
  the deployment guide relies on a host proxy/certificate.
- Exact Compose configuration validation with resolved settings failed because
  required environment values were unavailable. The no-interpolation validation
  checks only YAML/merge structure, not a deployable configuration.

## Verdict

The repository is Compose-shaped for a single host, but the AWS overlay cannot be
used safely as-is. Do not deploy until ports are private or explicitly
loopback-bound, firewall policy is verified, the health check is corrected, and
the actual sanitized environment configuration validates.

