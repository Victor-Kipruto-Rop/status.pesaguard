# Data Lineage Documentation

## Lineage Overview

PesaGuard analytics engineering maintains full data lineage from raw provider events
through to analytical marts. Each transformation is version-controlled and auditable.

## Lineage Chain

```
Provider Event (M-Pesa/Daraja Webhook)
    │
    ▼
PostgreSQL: transactions table (raw, immutable)
    │
    ▼
Analytics: stg_transactions (staging view, deduplicated)
    │
    ├──► int_transactions_enriched (silver, enriched)
    │         │
    │         ▼
    │         fct_transactions (gold, fact)
    │         │
    │         ▼
    │         mart_tenant_daily_financials (gold, mart)
    │
    ├──► int_reconciliation_outcomes (silver, categorized)
    │         │
    │         ▼
    │         fct_reconciliation (gold, fact)
    │
    └──► int_tenant_daily_summary (silver, aggregated)
              │
              ▼
              mart_tenant_daily_financials (gold, mart)

PostgreSQL: discrepancies table
    │
    ▼
Analytics: stg_discrepancies (staging view)
    │
    ▼
    int_transactions_enriched (silver, enrichment)
    │
    ▼
    fct_reconciliation (gold, fact linkage)

PostgreSQL: reconciliation_matches table
    │
    ▼
Analytics: stg_reconciliation_matches (staging view)
    │
    ▼
    int_reconciliation_outcomes (silver, categorization)
    │
    ▼
    fct_reconciliation (gold, fact)

PostgreSQL: merchant_metrics table
    │
    ▼
Analytics: stg_merchant_metrics (staging view)
    │
    ▼
    dim_merchant (gold, dimension)
```

## Lineage Tracking

The `AnalyticsEngine` tracks lineage through the `get_lineage()` method,
recording each model's dependencies and production timestamp.

## Auditability

Every model includes:
- `created_at` / `updated_at` timestamps from source
- `fact_created_at` for when the analytical record was produced
- Tenant isolation on all fields
- Surrogate keys generated deterministically from business keys
