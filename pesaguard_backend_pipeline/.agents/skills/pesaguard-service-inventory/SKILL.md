---
name: pesaguard-service-inventory
description: "Maps PesaGuard numbered services 1-162 to implementations and evidence. Use when auditing service coverage, prioritizing missing or partial work, updating the service matrix, continuing a service phase, or checking claims that a service is tested or production ready."
---

# PesaGuard Service Inventory

Maintain one evidence-based service inventory. The current source of truth is [SERVICE_COVERAGE_MATRIX.md](../../../docs/testing/SERVICE_COVERAGE_MATRIX.md); update it instead of creating a second competing catalog.

## Inventory procedure

1. Read the matrix legend, scope boundary, related service rows, product scope, and recent architecture notes.
2. Search the repository for the implementation, its public driver port, callers, tests, configuration, migrations, and operations/runbook coverage. Read the source and tests; filenames or class names alone do not prove working behavior.
3. For each service number, record:
   - Exact service name and current product-scope status.
   - Implementation modules and public entry points.
   - Unit and integration tests, plus security/performance/recovery evidence where applicable.
   - Status using the matrix's defined vocabulary and a concise, factual gap.
   - External systems or deployment conditions that remain unverified.
4. Separate these states:
   - **Implemented:** code exists.
   - **Tested:** relevant automated tests pass.
   - **Production-verified:** target-like deployment and applicable operational gates have direct evidence.
   - **Out of scope:** explicitly excluded by product scope or a recorded decision.
   - **Blocked:** work cannot be verified or completed because a named prerequisite is missing.
5. Never infer that one status implies another. In particular, implemented code is not tested, automated tests are not production verification, and a schema or placeholder is not an executable service.
6. Review numbering and coverage gaps. Preserve gaps in the requested inventory rather than renumbering or silently filling them with invented services. Where a number is absent from the requested list or matrix, record that fact and seek an owner decision before defining its behavior.
7. For a changed service, update its row and relevant phase-review notes in the same change as the code/tests/docs. State the exact command and result; do not write “all tests pass” if only a subset ran.

## Scope safeguards

- Follow [PRODUCT_SCOPE.md](../../../docs/product/PRODUCT_SCOPE.md). Payment origination, refunds, payouts, settlement execution, and fund movement are not implied by transaction ingestion or reconciliation.
- Treat each tenant-owned service as tenant-sensitive even when its current code appears shared. Trace authorization through routes, jobs, events, caches, exports, and persistence.
- Prioritize security, financial correctness, data integrity, and recovery gaps ahead of naming or abstraction gaps.
- Do not add code only to make a service count look complete. Reuse existing services and close the evidenced behavior gap.

## Completion criteria

An inventory pass is complete only when every in-scope row has a traceable implementation and test status, explicit blockers/out-of-scope decisions, and a truthful production-verification state. Open gaps remain visible and prioritized; the inventory is not “complete” merely because every number has a row.
