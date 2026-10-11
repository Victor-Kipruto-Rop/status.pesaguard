# Command Results and Limitations

Audit snapshot: 2026-10-11 11:27 UTC-equivalent timestamp in captured pytest output.
No secrets or resolved deployment environment values are included here.

| Command / probe | Outcome | Notes |
|---|---|---|
| `python -m alembic -c pesaguard_backend_pipeline\alembic.ini heads` | One head: `20261009_settlement_scheduler` | The first attempt failed because `JWT_SECRET_KEY` was absent. The successful read-only graph check used local audit-only placeholders; no secret value was emitted. |
| `python -m pytest -c pesaguard_backend_pipeline\pytest.ini -q --tb=short` | Collection aborted with 115 errors | Most errors were import-file mismatches into sibling `_backend-status-deploy`; one import error requested `_totp_code` from that checkout. No tests ran. Full captured output: tool-generated temp output from the audit session, not committed. |
| `docker compose -f ...docker-compose.yml -f ...docker-compose.aws.yml config --format json` | Failed required interpolation | Required database, Redis, bind host and metrics token-file settings were missing. No resolved config was emitted and no service started. |
| `docker compose -f ...docker-compose.yml -f ...docker-compose.aws.yml config --no-interpolate --no-env-resolution --format json` | YAML/merge parsed | Confirmed inherited host port mappings remain. Values were inspected in-memory; no resolved secret output was printed. |
| `python --version` | Python 3.13.15 | CI specifies Python 3.12; installed pytest was 7.4.4 while `requirements_3.txt` pins pytest 9.0.3. |
| `web_fetch` official pricing page probes | Incomplete pricing evidence | DigitalOcean size API returned 401; retrieved public pages did not yield comparable selected-tier rates; AWS official price list version was visible but instance price not extracted. |
| DNS/public endpoint probes from prior audit phase | API hostname did not resolve at probe time | No changes were made; recheck from deployment network before release. |

## Safety decisions

- No Docker services were started.
- No migration upgrade, database restore, payment, DNS, or deploy command was run.
- The fixed test database `%TEMP%\pesaguard-test.sqlite3` was confirmed absent before
  the first pytest attempt and exists now with a timestamp matching collection.
  `tests/conftest.py` deletes this path when it exists, so tests were not rerun.
- No live-test opt-in variables were set at the environment check; their values
  were not printed.
- The audit did not inspect `.env` contents or disclose credentials.

