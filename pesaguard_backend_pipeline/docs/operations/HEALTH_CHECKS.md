# API Health Checks

The canonical FastAPI entry point exposes separate process-liveness and
dependency-readiness probes:

| Endpoint | Purpose | Dependency probes | Status code |
|---|---|---|---|
| `GET /live` | Check that the API process can serve requests | None | `200` while the process is running |
| `GET /ready` | Check the configured dependency health before routing traffic | Database, Kafka, Redis, and Daraja | `200` when overall health is `ok`; otherwise `503` |
| `GET /health` | Backward-compatible detailed health contract | Database, Kafka, Redis, and Daraja | `200` when overall health is `ok`; otherwise `503` |

`/live` deliberately does not query dependencies. A downstream outage should
remove an instance from traffic through `/ready`, not trigger a liveness restart
loop. Readiness and health responses expose dependency status only; internal
addresses and error details are redacted.

Health responses use `Cache-Control: no-store`. Keep the probe routes reachable
only by trusted load balancers or orchestrator networks where deployment
networking permits it.

The readiness probe currently uses the shared dependency-health aggregate.
Kafka and Daraja probes have bounded connection timeouts; Daraja results are
cached. Do not configure an orchestrator to poll these endpoints more
frequently than its normal health-check interval. Confirm dependency-specific
readiness requirements and probe latency against the deployed environment
before using this API as a production traffic gate.
