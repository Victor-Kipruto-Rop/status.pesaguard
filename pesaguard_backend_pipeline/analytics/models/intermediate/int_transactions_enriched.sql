{{
  config(
    materialized="table",
    tags=["intermediate", "silver", "transactions"],
    description="Enriched transactions with staging joins and derived fields."
  )
}}
with stg as (
    select * from {{ ref('stg_transactions') }}
),
discrepancies as (
    select
        transaction_id,
        count(*) as discrepancy_count,
        max(detected_at) as latest_discrepancy_at,
        max(case when severity = 'critical' then 1 else 0 end) as has_critical_discrepancy
    from {{ ref('stg_discrepancies') }}
    where resolved = false
    group by transaction_id
),
reconciliation as (
    select
        transaction_id,
        max(match_score) as best_match_score,
        max(match_timestamp) as latest_match_timestamp,
        max(case when status = 'MATCHED' then 1 else 0 end) as is_reconciled
    from {{ ref('stg_reconciliation_matches') }}
    group by transaction_id
)
select
    s.transaction_id,
    s.external_transaction_id,
    s.tenant_id,
    s.provider_account_id,
    s.provider,
    s.idempotency_key,
    s.amount,
    s.currency,
    s.status,
    s.lifecycle_stage,
    s.msisdn,
    s.created_at,
    s.updated_at,
    s.version,
    coalesce(d.discrepancy_count, 0) as discrepancy_count,
    d.latest_discrepancy_at,
    d.has_critical_discrepancy,
    coalesce(r.best_match_score, 0) as best_match_score,
    r.latest_match_timestamp,
    coalesce(r.is_reconciled, 0) as is_reconciled,
    case
        when d.has_critical_discrepancy = 1 then 'flagged'
        when r.is_reconciled = 1 then 'reconciled'
        when d.discrepancy_count > 0 then 'disputed'
        else s.status
    end as enriched_status
from stg s
left join discrepancies d on s.transaction_id = d.transaction_id
left join reconciliation r on s.transaction_id = r.transaction_id
