{{
  config(
    materialized="table",
    tags=["marts", "gold", "dimension", "tenant"],
    description="Tenant dimension - one row per tenant with tenant-level attributes."
  )
}}
with tenants as (
    select distinct
        tenant_id,
        min(created_at) as first_seen_at
    from {{ ref('stg_transactions') }}
    group by tenant_id
),
tenant_stats as (
    select
        tenant_id,
        count(*) as total_transactions,
        sum(amount) as total_volume,
        max(created_at) as last_transaction_at
    from {{ ref('stg_transactions') }}
    group by tenant_id
)
select
    {{ dbt_utils.surrogate_key(['tenant_id']) }} as tenant_key,
    t.tenant_id,
    t.first_seen_at,
    ts.total_transactions,
    ts.total_volume,
    ts.last_transaction_at,
    current_timestamp as updated_at
from tenants t
left join tenant_stats ts on t.tenant_id = ts.tenant_id
