{{
  config(
    materialized="table",
    tags=["intermediate", "silver", "reconciliation"],
    description="Reconciliation outcomes with match classifications and latency metrics."
  )
}}
with matches as (
    select
        match_id,
        tenant_id,
        transaction_id,
        match_score,
        match_timestamp,
        engine_version,
        status,
        processing_latency_ms,
        created_at
    from {{ ref('stg_reconciliation_matches') }}
),
categorized as (
    select
        *,
        case
            when match_score >= 0.95 then 'exact_match'
            when match_score >= 0.80 then 'near_match'
            when match_score >= 0.50 then 'partial_match'
            else 'mismatch'
        end as match_category,
        case
            when processing_latency_ms <= 100 then 'fast'
            when processing_latency_ms <= 1000 then 'normal'
            else 'slow'
        end as latency_category
    from matches
)
select
    match_id,
    tenant_id,
    transaction_id,
    match_score,
    match_timestamp,
    engine_version,
    status,
    processing_latency_ms,
    created_at,
    match_category,
    latency_category
from categorized
