# VPS and Hosting Cost Estimate

## Result: blocked, not zero

The audit brief requires current provider pricing in USD and KSh. A reliable total
cannot be quoted from repository evidence because there is no selected provider or
exact region, no approved residency mapping, no storage/traffic workload, and no
backup destination. The current `ke-1` value is a logical default; the data-residency
document notes AWS Cape Town is in South Africa, not in Kenya, and the exact
provider/region is pending. No exchange-rate assumption was established.

Public vendor pricing pages were queried during the audit, but accessible results
did not supply a comparable selected plan quote. DigitalOcean's public sizing API
required authentication (401); the pricing page output did not expose a verifiable
instance rate; Hetzner's page established its served regions but did not expose
itemized rates in the retrieved content; Render's page confirmed per-service
proration but did not expose a complete equivalent stack price. The AWS official
price-list endpoint identified a Cape Town publication version, but no instance
price was extracted. No KSh conversion is provided rather than inventing one.

## Required comparison

| Scenario | Included cost elements | Monthly USD | Monthly KSh | Status |
|---|---|---:|---:|---|
| A. One VPS, full Compose stack | Linux compute, SSD, bandwidth, public IP, off-site encrypted backup storage, monitoring/egress | **TBD** | **TBD** | Provider, region, sizing and usage not selected. |
| B. VPS + managed PostgreSQL | VPS for API/workers/broker/cache, managed DB tier/storage/backup/egress, VPS storage and backup | **TBD** | **TBD** | Managed Postgres availability/pricing in approved region unknown. |
| C. VPS + managed DB + messaging | VPS plus managed Postgres, Kafka-compatible messaging, Redis, transfer and backups | **TBD** | **TBD** | No region/service decision or workload estimate. |
| D. Managed application hosting comparison | Render (or selected equivalent) compute per API/worker, DB, Redis, broker, bandwidth and backup | **TBD** | **TBD** | Existing workflow targets Render, but deployed plan/configuration and equivalent service costs are unknown. |

No option should be labeled free or production-ready based on a free tier. DNS cost
is domain/registrar-specific and already-owned-domain renewal was not investigated.
Taxes, FX spread, transaction egress, email/SMS provider use, and backup storage are
excluded until measured or quoted.

## Capacity candidate implications

The repository EC2 guide documents 2 vCPU, 8 GiB RAM, and at least 50 GiB gp3 as
the starting size for the full stack. This is the only repository-backed sizing
baseline; it is not a measured minimum, quote, or proof of steady-state headroom.
The 1 vCPU/2 GiB and 2 vCPU/4 GiB candidates are not supportable for the complete
stack without measurement given the broker's 1 GiB memory setting plus the database,
APIs, workers, and monitoring. An 8 GiB/4 vCPU candidate is a reasonable isolated
test starting point, not a production capacity certification. Live volume cannot be
estimated without a representative benchmark.

## Inputs required for a priced estimate

1. Provider and exact region, including residency approval.
2. VPS plan, CPU/RAM/disk and public IPv4 pricing.
3. Expected monthly egress, webhook traffic, database growth, Kafka retention, and
   backup size/retention.
4. Self-hosted versus managed Postgres, Redis, messaging, and monitoring.
5. Required availability, snapshot cadence, cross-zone/network transfer, and taxes.
6. CBK or finance-approved USD/KES rate and date.

