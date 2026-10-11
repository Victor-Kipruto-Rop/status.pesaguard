# Configuration Findings

## Compose port merge

The effective no-interpolation merge of
`infra/docker/docker-compose.yml` and `infra/docker/docker-compose.aws.yml` retained:

| Host port | Container port | Service | Host binding |
|---:|---:|---|---|
| 5000 | 5000 | webhook_receiver | all interfaces (no `host_ip`) |
| 5001 | 5001 | dashboard_api | all interfaces (no `host_ip`) |
| 5433 | 5432 | postgres | all interfaces (no `host_ip`) |
| 6379 | 6379 | redis | all interfaces (no `host_ip`) |
| 19092 / 8082 / 9644 | same | redpanda | all interfaces (no `host_ip`) |
| 9090 / 9093 / 3000 | same | Prometheus / Alertmanager / Grafana | all interfaces (no `host_ip`) |
| 4317 / 4318 / 8888 / 8889 | same | otel_collector | all interfaces (no `host_ip`) |
| 8080 | 8080 | nginx | `127.0.0.1` |

The `ports: []` entries on `webhook_receiver` and `dashboard_api` in the AWS overlay
do not clear mappings inherited from the base file. This confirms the warning already
documented in `infra/README.md`. The exact merge was validated with interpolation
and env-file resolution disabled; a fully resolved target config remains blocked
until required settings are provisioned.

## Health-check port

- `infra/docker/Dockerfile` probes `http://127.0.0.1:${PORT:-5001}/health`.
- `infra/docker/docker-compose.yml` starts `webhook_receiver` with
  `python pesaguard_backend_pipeline/app.py`, without a `PORT` override.
- `app.py` defaults to port 5000.
- Therefore the inherited webhook health check defaults to 5001 while that process
  defaults to 5000. Verify/fix before deployment.

## Other configuration notes

- Required Compose settings include Postgres identity/password, container DB URL,
  API URL, JWT secret, Kafka bootstrap, Redis URL, bind host, and a metrics token
  file. AWS overlay additionally requires `API_SERVER_NAME`.
- No values are reproduced here. The deployment env file is ignored and was not
  read or printed.
- Base Compose pins some images, but Prometheus, Grafana and OTEL use `latest`.
- PostgreSQL and Redpanda have named volumes; Redis and monitoring data do not have
  explicit volumes in the base file.
- Compose does not set explicit restart policies in the reviewed services.
- The AWS deployment guide references the older `api.pesaguard.victorkipruto.com`
  hostname; the requested primary is `api.pesaguard.co.ke`.
- Residency defaults to `ke-1`, but documentation says exact hosting provider and
  region await confirmation. `ke-1` must not be represented as a verified Kenyan
  data-center location.

