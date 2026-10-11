# Testing and Verification Results

## Commands and outcomes

| Check | Command / method | Result |
|---|---|---|
| Migration graph | `python -m alembic -c pesaguard_backend_pipeline\alembic.ini heads` with local-only placeholder variables | One head: `20261009_settlement_scheduler`. No database connection or migration was run. |
| Full pytest suite | `python -m pytest -c pesaguard_backend_pipeline\pytest.ini -q --tb=short` | **Collection aborted:** 115 collection errors; no tests executed and no pass/fail/skip counts exist. |
| Compose resolved config | `docker compose ... config --format json` | Failed because required deployment environment values were unavailable. No services started. |
| Compose merge structure | `docker compose -f ...docker-compose.yml -f ...docker-compose.aws.yml config --no-interpolate --no-env-resolution --format json` | Parsed the YAML/merge; confirmed base ports remain. Does not validate deployment interpolation or secrets. |
| Public price/recovery/deployment verification | Official pricing-page probes and repository inspection | No selected provider/region price or FX rate could be established; no VPS/backup/deployment was available. |

The captured test output showed modules imported from
`C:\Users\kipru\pesaguard\_backend-status-deploy\...` while pytest was collecting
the corresponding files under `C:\Users\kipru\pesaguard\pesaguard_backend_pipeline\...`.
One import error also requested `_totp_code` from the sibling checkout's
`app_4_advanced_features.py`. This makes the suite result inconclusive about
application correctness and a concrete test-environment/checkout isolation blocker.

The project test fixture deletes the fixed `%TEMP%\pesaguard-test.sqlite3` if it
exists. That file was absent before the attempted run and was created by collection.
It exists now, so the suite was not rerun to avoid deleting or replacing a database
at that fixed path. No opt-in live-test flag names were set at the time of the
environment check.

## Test categories not verified

Unit, API contract, PostgreSQL concurrency, Redis integration, Kafka/Redpanda
integration, payment sandbox, email/SMS, deployment smoke, security, recovery, and
performance results are all **UNVERIFIED**. CI YAML contains checks and deployment
steps, but no successful run was reviewed.

## Safe next run

First isolate the source checkout and test import path, use a unique temporary
directory/database that cannot collide with the fixture's fixed path, and execute
on the CI-supported Python 3.12 environment. Confirm all live/integration opt-in
flags are unset; use synthetic fixtures only. Record exact pass/fail/skip counts.
Then run isolated Postgres/Redis/Redpanda integration tests. Do not point tests at
production or perform live provider transactions.

