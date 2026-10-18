{{
  config(
    materialized="table",
    tags=["marts", "gold", "mart", "financials"],
    description="Tenant daily financials mart - daily financial aggregates by tenant."
  )
}}
with daily as (
    select * from {{ ref('int_tenant_daily_summary') }}
),
tenant_dim as (
    select tenant_id, tenant_key from {{ ref('dim_tenant') }}
),
date_dim as (
    select date_value, date_key from {{ ref('dim_date') }}
)
select
    {{ dbt_utils.surrogate_key(['d.tenant_id', 'd.trans_date']) }} as financial_fact_key,
    td.tenant_key,
    dd.date_key,
    d.tenant_id,
    d.trans_date,
    d.currency,
    d.provider,
    d.transaction_count,
    d.total_volume,
    d.failed_count,
    d.reconciled_count,
    d.pending_count,
    d.reconciliation_rate,
    d.failure_rate,
    d.discrepancy_count,
    d.critical_discrepancies,
    d.open_discrepancies,
    d.updated_at
from daily d
join tenant_dim td on d.tenant_id = td.tenant_id
join date_dim dd on d.trans_date = dd.date_value
