# Analytics Model Documentation

## Staging Models (Bronze)

### stg_transactions
**Source:** `transactions` table
**Materialization:** View
**Description:** Staged raw M-Pesa/Daraja transactions, deduplicated by tenant + provider + external transaction ID.
**Key fields:** transaction_id, external_transaction_id, tenant_id, provider_account_id, provider, amount, currency, status, lifecycle_stage, created_at

### stg_discrepancies
**Source:** `discrepancies` table
**Materialization:** View
**Description:** Staged reconciliation discrepancies requiring investigation.
**Key fields:** discrepancy_id, transaction_id, tenant_id, anomaly_type, severity, detected_at, resolved

### stg_reconciliation_matches
**Source:** `reconciliation_matches` table
**Materialization:** View
**Description:** Staged reconciliation match results with match scores and processing latency.
**Key fields:** match_id, tenant_id, transaction_id, match_score, match_timestamp, engine_version, status

### stg_merchant_metrics
**Source:** `merchant_metrics` table
**Materialization:** View
**Description:** Staged incremental merchant metrics by time bucket (hour/day/week).
**Key fields:** metric_id, tenant_id, merchant_id, currency, granularity, bucket_start, transaction_count, total_volume

## Intermediate Models (Silver)

### int_transactions_enriched
**Sources:** stg_transactions, stg_discrepancies, stg_reconciliation_matches
**Materialization:** Table
**Description:** Enriched transactions with discrepancy counts, reconciliation status, and enriched status classification.
**Key fields:** transaction_id, tenant_id, amount, currency, status, enriched_status, discrepancy_count, best_match_score, is_reconciled

### int_tenant_daily_summary
**Sources:** stg_transactions, stg_discrepancies
**Materialization:** Table
**Description:** Daily tenant-level summary of transaction volumes, failure rates, reconciliation rates, and discrepancy counts.
**Key fields:** tenant_id, trans_date, currency, provider, transaction_count, total_volume, reconciliation_rate, failure_rate, discrepancy_count

### int_reconciliation_outcomes
**Source:** stg_reconciliation_matches
**Materialization:** Table
**Description:** Categorized reconciliation match outcomes with match categories (exact/near/partial/mismatch) and latency categories (fast/normal/slow).
**Key fields:** match_id, tenant_id, transaction_id, match_score, match_category, latency_category, processing_latency_ms

## Marts Models (Gold)

### dim_tenant
**Source:** stg_transactions
**Materialization:** Table
**Description:** Tenant dimension with first-seen date, total transactions, and total volume.

### dim_merchant
**Source:** stg_merchant_metrics
**Materialization:** Table
**Description:** Merchant dimension with transaction counts, volumes, failure/reconciliation/anomaly rates by currency.

### dim_provider
**Source:** stg_transactions
**Materialization:** Table
**Description:** Provider dimension with transaction counts, volumes, and reconciliation rates by provider.

### dim_date
**Source:** stg_transactions (date range)
**Materialization:** Table
**Description:** Date dimension with year, month, day, week, day-of-week, weekend flag.

### fct_reconciliation
**Sources:** int_reconciliation_outcomes, stg_discrepancies, dim_date
**Materialization:** Table
**Description:** Reconciliation fact table linking match outcomes to dates and discrepancies.
**Key fields:** reconciliation_fact_key, match_id, tenant_id, transaction_id, match_score, match_category, latency_category, related_discrepancies

### fct_transactions
**Sources:** int_transactions_enriched, dim_date
**Materialization:** Table
**Description:** Transaction fact table linking enriched transactions to dates.
**Key fields:** transaction_fact_key, transaction_id, tenant_id, amount, currency, transaction_status, enriched_status, is_reconciled

### mart_tenant_daily_financials
**Sources:** int_tenant_daily_summary, dim_tenant, dim_date
**Materialization:** Table
**Description:** Tenant daily financials mart - the primary reporting table for daily financial dashboards.
**Key fields:** financial_fact_key, tenant_key, date_key, tenant_id, trans_date, transaction_count, total_volume, reconciliation_rate, discrepancy_count
