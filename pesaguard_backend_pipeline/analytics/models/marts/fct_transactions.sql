{{
  config(
    materialized="table",
    tags=["marts", "gold", "transactions", "fact"],
    description="Transaction fact table - one row per enriched transaction with measures."
  )
}}
with enriched as (
    select * from {{ ref('int_transactions_enriched') }}
)
select
    {{ dbt_utils.surrogate_key(['transaction_id']) }} as transaction_fact_key,
    e.transaction_id,
    e.tenant_id,
    e.provider_account_id,
    e.provider,
    d.date_key,
    e.amount,
    e.currency,
    e.status as transaction_status,
    e.enriched_status,
    e.discrepancy_count,
    e.has_critical_discrepancy,
    e.best_match_score,
    e.is_reconciled,
    e.created_at as fact_created_at
from enriched e
left join {{ ref('dim_date') }} d
    on date(e.created_at) = d.date_value
