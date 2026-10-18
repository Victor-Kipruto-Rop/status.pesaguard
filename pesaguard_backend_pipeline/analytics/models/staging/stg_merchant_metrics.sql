{{
  config(
    materialized="view",
    tags=["staging", "bronze", "merchant_metrics"],
    description="Staging model for incremental merchant metrics."
  )
}}
with source as (
    select
        id as metric_id,
        tenant_id,
        merchant_id,
        currency,
        granularity,
        bucket_start,
        transaction_count,
        total_volume,
        average_transaction,
        failed_transactions,
        reconciled_transactions,
        anomaly_count,
        updated_at
    from {{ ref('merchant_metrics') }}
    where tenant_id is not null and tenant_id <> ''
)
select
    metric_id,
    tenant_id,
    merchant_id,
    currency,
    granularity,
    bucket_start,
    transaction_count,
    total_volume,
    average_transaction,
    failed_transactions,
    reconciled_transactions,
    anomaly_count,
    updated_at
from source
