{{
  config(
    materialized="table",
    tags=["marts", "gold", "dimension", "merchant"],
    description="Merchant dimension - one row per merchant with merchant-level attributes."
  )
}}
with merchants as (
    select distinct
        merchant_id,
        currency
    from {{ ref('stg_merchant_metrics') }}
),
merchant_stats as (
    select
        merchant_id,
        currency,
        sum(transaction_count) as total_transactions,
        sum(total_volume) as total_volume,
        sum(failed_transactions) as total_failed,
        sum(reconciled_transactions) as total_reconciled,
        sum(anomaly_count) as total_anomalies,
        max(updated_at) as last_updated
    from {{ ref('stg_merchant_metrics') }}
    group by merchant_id, currency
)
select
    {{ dbt_utils.surrogate_key(['merchant_id', 'currency']) }} as merchant_key,
    m.merchant_id,
    m.currency,
    ms.total_transactions,
    ms.total_volume,
    ms.total_failed,
    ms.total_reconciled,
    ms.total_anomalies,
    case when ms.total_transactions > 0 then round(ms.total_reconciled::numeric / ms.total_transactions, 4) else 0 end as reconciliation_rate,
    ms.last_updated
from merchants m
left join merchant_stats ms
    on m.merchant_id = ms.merchant_id and m.currency = ms.currency
