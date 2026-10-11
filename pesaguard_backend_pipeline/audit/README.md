# PesaGuard Core API VPS Readiness Audit

Read-only assessment of the existing backend for deployment to a single Linux VPS.
The audit snapshot is `main` at `8c50cad38e5176aec2513a6ef4584493364a1d42`, with
a dirty worktree. No application, infrastructure, DNS, database, or payment changes
were made as part of the assessment.

## Decision

**NOT READY FOR VPS DEPLOYMENT.** The Compose AWS overlay leaves internal ports
published on all interfaces; test collection is blocked by imports resolving into
a sibling checkout; production-region and off-site recovery choices are unverified;
and there is no target-like capacity, integration, or recovery evidence.

## Reports

- [Executive summary](executive-summary.md)
- [Repository inventory](repository-inventory.md)
- [Architecture](architecture.md)
- [Service dependency matrix](service-dependency-matrix.md)
- [Docker/VPS compatibility](docker-vps-compatibility.md)
- [Security audit](security-audit.md)
- [Database integrity](database-integrity.md)
- [Messaging and workers](messaging-and-workers.md)
- [Testing results](testing-results.md)
- [Monitoring and recovery](monitoring-and-recovery.md)
- [Cost estimate](cost-estimate.md)
- [Deployment plan](deployment-plan.md)
- [Findings register](findings-register.md)
- [Command results](evidence/command-results.md)
- [Configuration findings](evidence/configuration-findings.md)

The verdict is based on the production-readiness gates in
`pesaguard_backend_pipeline/.agents/skills/pesaguard-production-readiness/SKILL.md`.
Source code and local configuration are not treated as proof of successful live
behavior. See each report for evidence and unverified areas.
