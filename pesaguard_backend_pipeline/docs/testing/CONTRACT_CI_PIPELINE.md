# Contract CI/CD Pipeline

> **Purpose:** Describe the automated data-contract pipeline that guards every
> PesaGuard contract change, and map each stage to the exact command that
> enforces it. The executable definition is
> [`.github/workflows/contracts.yml`](../../.github/workflows/contracts.yml).

## Flow

```
Developer changes contract
            │
            ▼
       Pull Request                (contracts.yml triggers)
            │
            ▼
      Schema linting               job: schema-linting
            │
            ▼
     Schema validation             job: schema-validation
            │
            ▼
 Compatibility checking            job: compatibility-checking
            │
            ▼
 Breaking-change detection         job: breaking-change-detection
            │
            ▼
    Producer contract tests        job: producer-contract-tests
            │
            ▼
    Consumer contract tests        job: consumer-contract-tests
            │
            ▼
     Integration tests             job: integration-tests
            │
            ▼
        Human review               CODEOWNERS + pull_request_template.md
            │
            ▼
          Merge                    branch protection
            │
            ▼
         Deploy                    release.yml / docker-image.yml
```

Stages 1-7 are automated jobs. The final `contract-gate` job depends on all of
them and is the single status check to make required in branch protection, so
review is only requested once every automated stage is green.

## Stage reference

| # | Stage | Job | Enforced by | Fails when |
|---|---|---|---|---|
| 1 | Schema linting | `schema-linting` | `pesaguard_backend_pipeline/contract_lint.py` | A contract is unparseable, off the 2020-12 draft, missing `$id`, has a duplicate `$id`, has no root shape and no `$defs`, has an unresolvable `$ref`, or has drifted from `registry.json` in either direction |
| 2 | Schema validation | `schema-validation` | `test_schema_registry.py`, `test_schema_validation.py`, `test_api_schema_validation.py`, `test_contract_lint.py` | A registered schema is structurally invalid or a payload no longer validates against its contract |
| 3 | Compatibility checking | `compatibility-checking` | `test_schema_compatibility.py`, `test_contract_versioning.py`, `test_backward_compatibility.py`, `test_contract_diff.py` | A change violates the declared compatibility policy, uses an unsupported contract version, or breaks a v1 consumer |
| 4 | Breaking-change detection | `breaking-change-detection` | `pesaguard_backend_pipeline/contract_diff.py` against the PR base revision | A contract field is removed, becomes required, changes type/format/unit/precision, an enum value is removed, a contract file is deleted, a schema is unregistered, or an OpenAPI path/method/response is removed |
| 5 | Producer contract tests | `producer-contract-tests` | `test_contract_testing.py -k "not consumer"`, `test_source_contracts.py` | A produced envelope violates the versioned envelope contract, an event type loses its topic route, the topic inventory drifts, or a provider payload leaves its source contract |
| 6 | Consumer contract tests | `consumer-contract-tests` | `test_contract_testing.py -k "consumer"`, `test_audit_event_contract.py`, `test_backward_compatibility.py` | A consumer rejects a valid event, a malformed event is not dead-lettered, persistence stops being idempotent, or a consumer cannot read the previous contract version |
| 7 | Integration tests | `integration-tests` | `pytest` with PostgreSQL 15 and Redis 7 service containers | The suite fails against real PostgreSQL and Redis |
| 8 | Contract gate | `contract-gate` | `jq` over `needs` | Any stage above did not return `success` |
| 9 | Human review | *(not a job)* | `.github/CODEOWNERS`, `.github/pull_request_template.md` | — |
| 10-11 | Merge and deploy | *(not this workflow)* | Branch protection, `release.yml`, `docker-image.yml` | — |

## Design decisions

- **No `paths:` filter.** A required status check that is filtered out never
  reports, which leaves pull requests blocked indefinitely. The workflow runs on
  every pull request to `main` and `staging`.
- **`fetch-depth: 0` in stage 4 only.** Comparing a contract against a base
  revision needs history; the other stages do not.
- **Stage 4 is a no-op, not a skip, where no base revision exists.**
  `contract_diff.py` exits `2` when a base cannot be resolved, so the workflow
  resolves `HEAD~1` first and skips the diff step with an explicit message when
  there is genuinely no parent commit. The job still reports `success`.
- **Stage 3 validates the tooling.** Both new CLIs have their own tests, so a
  broken linter or comparator fails the pipeline instead of silently passing
  contracts.
- **Stage 6 uses two separate steps on purpose.** `pytest -k` applies to every
  file in the same invocation, so combining `-k "consumer"` with unrelated test
  files silently deselects their tests.

## Reproducing a stage locally

```bash
# 1. Schema linting
python pesaguard_backend_pipeline/contract_lint.py

# 2. Schema validation
pytest -v pesaguard_backend_pipeline/tests/test_schema_registry.py \
          pesaguard_backend_pipeline/tests/test_schema_validation.py \
          pesaguard_backend_pipeline/tests/test_api_schema_validation.py

# 3. Compatibility checking
pytest -v pesaguard_backend_pipeline/tests/test_schema_compatibility.py \
          pesaguard_backend_pipeline/tests/test_contract_versioning.py \
          pesaguard_backend_pipeline/tests/test_backward_compatibility.py

# 4. Breaking-change detection against a base revision
python pesaguard_backend_pipeline/contract_diff.py --base-ref origin/main

# 5. Producer contract tests
pytest -v pesaguard_backend_pipeline/tests/test_contract_testing.py -k "not consumer"
pytest -v pesaguard_backend_pipeline/tests/test_source_contracts.py

# 6. Consumer contract tests
pytest -v pesaguard_backend_pipeline/tests/test_contract_testing.py -k "consumer"
pytest -v pesaguard_backend_pipeline/tests/test_audit_event_contract.py \
          pesaguard_backend_pipeline/tests/test_backward_compatibility.py

# 7. Integration tests (requires PostgreSQL and Redis)
pytest -v --tb=short
```

## Required repository settings (manual)

These are GitHub settings, not repository files, so they cannot be committed:

1. **Branch protection** for `main` and `staging`:
   - Require status checks to pass, and select **`8. Contract gate`**.
   - Require review from Code Owners.
2. **Repository access**: every handle in `.github/CODEOWNERS` must have write
   access, otherwise GitHub cannot request them as reviewers.

## Known overlap

`ci.yml` also runs the OpenAPI breaking-change comparison. `contracts.yml`
supersedes it for event, source, and OpenAPI contracts together. Removing the
duplicate step from `ci.yml` is a separate, deliberate change and has been left
untouched so that this pipeline stays purely additive.

## Related documents

- [`CONTRACT_TESTING.md`](CONTRACT_TESTING.md) — the contract-testing design baseline.
- [`../architecture/CONTRACT_OWNERSHIP.md`](../architecture/CONTRACT_OWNERSHIP.md) — owners, producers, and consumers per contract.
- [`../architecture/EVENT_ARCHITECTURE.md`](../architecture/EVENT_ARCHITECTURE.md) — event taxonomy and routing.
