{{
  config(
    materialized="table",
    tags=["marts", "gold", "dimension", "provider"],
    description="Provider dimension - one row per payment provider."
  )
}}
with providers as (
    select distinct
        provider
    from {{ ref('stg_transactions') }}
),
provider_stats as (
    select
        provider,
        count(*) as total_transactions,
        sum(amount) as total_volume,
        sum(case when status in ('FAILED', 'REJECTED', 'REVERSED') then 1 else 0 end) as total_failed,
        sum(case when status in ('RECONCILED', 'MATCHED', 'COMPLETED') then 1 else 0 end) as total_reconciled,
        max(created_at) as last_transaction_at
    from {{ ref('stg_transactions') }}
    group by provider
)
select
    {{ dbt_utils.surrogate_key(['provider']) }} as provider_key,
    p.provider,
    ps.total_transactions,
    ps.total_volume,
    ps.total_failed,
    ps.total_reconciled,
    case when ps.total_transactions > 0 then round(ps.total_reconciled::numeric / ps.total_transactions, 4) else 0 end as reconciliation_rate,
    ps.last_transaction_at,
    current_timestamp as updated_at
from providers p
left join provider_stats ps on p.provider = ps.provider
