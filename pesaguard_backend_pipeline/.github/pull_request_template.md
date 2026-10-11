## What contract changed

<!-- Name the affected contracts and topics, for example:
     transaction.received (pesaguard.transactions.raw), docs/api/openapi.json -->

## Why

<!-- The business or technical reason for the change. Link the ADR or issue. -->

## Contract pipeline checklist

Tick every line. The `8. Contract gate` check in
`.github/workflows/contracts.yml` enforces the automated stages; the reviewers
enforce the rest.

- [ ] **Schema linting** — `python pesaguard_backend_pipeline/contract_lint.py` passes.
- [ ] **Schema validation** — payloads still validate against the contract.
- [ ] **Compatibility checking** — the change respects the policy in
      `pesaguard_backend_pipeline/schemas/registry.json`.
- [ ] **Breaking-change detection** — `python pesaguard_backend_pipeline/contract_diff.py --base-ref <base>`
      reports no breaking change, **or** the breaking change is stated below
      with its migration and rollout plan.
- [ ] **Producer contract tests** — producer-side tests updated and passing.
- [ ] **Consumer contract tests** — every consumer listed in
      `docs/architecture/CONTRACT_OWNERSHIP.md` still satisfies the contract.
- [ ] **Integration tests** — the suite passes against PostgreSQL and Redis.
- [ ] **Ownership** — `docs/architecture/CONTRACT_OWNERSHIP.md` is updated if a
      producer, consumer, or owner changed.

## Breaking change disclosure (required when the gate reports one)

- Impacted contracts and topics:
- Impacted consumers:
- Migration / dual-publish window:
- Rollback plan:
- Approval from the contract owner:

## Rollout and rollback

<!-- Deployment ordering, feature flags, and how to revert safely. -->
