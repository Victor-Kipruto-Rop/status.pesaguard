{{
  config(
    materialized="table",
    tags=["marts", "gold", "reconciliation", "fact"],
    description="Reconciliation fact table - one row per reconciliation match with outcomes."
  )
}}
with int_outcomes as (
    select * from {{ ref('int_reconciliation_outcomes') }}
),
discrepancies as (
    select
        transaction_id,
        count(*) as related_discrepancies,
        sum(case when severity = 'critical' then 1 else 0 end) as critical_discrepancies
    from {{ ref('stg_discrepancies') }}
    group by transaction_id
)
select
    {{ dbt_utils.surrogate_key(['match_id']) }} as reconciliation_fact_key,
    o.match_id,
    o.tenant_id,
    o.transaction_id,
    d.date_key,
    o.match_score,
    o.match_category,
    o.latency_category,
    o.processing_latency_ms,
    o.status as match_status,
    coalesce(d.related_discrepancies, 0) as related_discrepancies,
    coalesce(d.critical_discrepancies, 0) as critical_discrepancies,
    o.created_at as fact_created_at
from int_outcomes o
left join {{ ref('dim_date') }} d
    on date(o.match_timestamp) = d.date_value
left join discrepancies d on o.transaction_id = d.transaction_id
