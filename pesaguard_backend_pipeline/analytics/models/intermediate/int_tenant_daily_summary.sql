{{
  config(
    materialized="table",
    tags=["intermediate", "silver", "aggregates"],
    description="Daily tenant-level summary of transaction volumes and reconciliation outcomes."
  )
}}
with daily_txns as (
    select
        tenant_id,
        date(created_at) as trans_date,
        currency,
        provider,
        count(*) as transaction_count,
        sum(amount) as total_volume,
        sum(case when status in ('FAILED', 'REJECTED', 'REVERSED') then 1 else 0 end) as failed_count,
        sum(case when status in ('RECONCILED', 'MATCHED', 'COMPLETED') then 1 else 0 end) as reconciled_count,
        sum(case when status = 'PENDING' then 1 else 0 end) as pending_count
    from {{ ref('stg_transactions') }}
    group by tenant_id, date(created_at), currency, provider
),
daily_discrepancies as (
    select
        tenant_id,
        date(detected_at) as trans_date,
        count(*) as discrepancy_count,
        sum(case when severity = 'critical' then 1 else 0 end) as critical_discrepancies,
        sum(case when resolved = false then 1 else 0 end) as open_discrepancies
    from {{ ref('stg_discrepancies') }}
    group by tenant_id, date(detected_at)
)
select
    t.tenant_id,
    t.trans_date,
    t.currency,
    t.provider,
    t.transaction_count,
    t.total_volume,
    t.failed_count,
    t.reconciled_count,
    t.pending_count,
    case when t.transaction_count > 0 then round(t.reconciled_count::numeric / t.transaction_count, 4) else 0 end as reconciliation_rate,
    case when t.transaction_count > 0 then round(t.failed_count::numeric / t.transaction_count, 4) else 0 end as failure_rate,
    coalesce(d.discrepancy_count, 0) as discrepancy_count,
    coalesce(d.critical_discrepancies, 0) as critical_discrepancies,
    coalesce(d.open_discrepancies, 0) as open_discrepancies,
    current_timestamp as updated_at
from daily_txns t
left join daily_discrepancies d
    on t.tenant_id = d.tenant_id and t.trans_date = d.trans_date
