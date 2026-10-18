# PesaGuard Analytics Engineering

Analytics engineering layer for PesaGuard, providing dbt-compatible SQL models
and a Python execution engine for transforming raw operational data into
analytical marts.

## Structure

```
analytics/
├── dbt_project.yml          # dbt project configuration
├── profiles.yml.example     # Example dbt profiles (copy to profiles.yml)
├── metrics.yml              # Metric definitions (dbt metrics)
├── analytics_engine.py      # Python execution engine
├── README.md                # This file
├── models/
│   ├── macros/
│   │   └── tenant_isolation.sql
│   ├── staging/
│   │   ├── stg_transactions.sql
│   │   ├── stg_discrepancies.sql
│   │   ├── stg_reconciliation_matches.sql
│   │   ├── stg_merchant_metrics.sql
│   │   └── schema.yml
│   ├── intermediate/
│   │   ├── int_transactions_enriched.sql
│   │   ├── int_tenant_daily_summary.sql
│   │   ├── int_reconciliation_outcomes.sql
│   │   └── schema.yml
│   └── marts/
│       ├── fct_reconciliation.sql
│       ├── fct_transactions.sql
│       ├── dim_tenant.sql
│       ├── dim_merchant.sql
│       ├── dim_provider.sql
│       ├── dim_date.sql
│       ├── mart_tenant_daily_financials.sql
│       └── schema.yml
├── docs/
│   ├── MODELS.md            # Model documentation
│   ├── LINEAGE.md           # Data lineage documentation
│   └── METRICS.md           # Metric definitions documentation
└── seeds/                   # Seed data (empty)
```

## Layer Architecture

### Bronze (Staging)
- `stg_transactions` - Raw M-Pesa/Daraja transactions, deduplicated
- `stg_discrepancies` - Reconciliation discrepancies
- `stg_reconciliation_matches` - Reconciliation match results
- `stg_merchant_metrics` - Incremental merchant metrics

### Silver (Intermediate)
- `int_transactions_enriched` - Enriched with discrepancy/reconciliation context
- `int_tenant_daily_summary` - Daily tenant-level aggregates
- `int_reconciliation_outcomes` - Categorized match outcomes

### Gold (Marts)
- `fct_reconciliation` - Reconciliation fact table
- `fct_transactions` - Transaction fact table
- `dim_tenant` - Tenant dimension
- `dim_merchant` - Merchant dimension
- `dim_provider` - Provider dimension
- `dim_date` - Date dimension
- `mart_tenant_daily_financials` - Tenant daily financials mart

## Data Tests

Each model has schema tests defined in `schema.yml` files:
- `unique` - Primary keys must be unique
- `not_null` - Required fields must not be null
- `accepted_values` - Enforced domains for categorical fields

## Execution

The `AnalyticsEngine` class can execute all models against a SQLAlchemy session:

```python
from analytics.analytics_engine import AnalyticsEngine
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

engine = create_engine("postgresql://user:pass@localhost/pesaguard")
Session = sessionmaker(bind=engine)
session = Session()

analytics = AnalyticsEngine(session)
results = analytics.execute_all(tenant_id="tenant-a")
test_results = analytics.run_all_tests()
summary = analytics.get_summary()
```

## Tenant Isolation

All models filter by `tenant_id` and the execution engine supports
tenant-scoped execution via the `tenant_id` parameter.

## Metrics

Defined in `metrics.yml`:
- `transaction_volume` - Cumulative transaction volume
- `transaction_count` - Transaction count
- `reconciliation_rate` - Reconciliation success rate
- `discrepancy_count` - Open discrepancy count
- `avg_processing_latency_ms` - Average processing latency
- `merchant_failure_rate` - Merchant failure rate
- `anomaly_rate` - Anomaly detection rate
