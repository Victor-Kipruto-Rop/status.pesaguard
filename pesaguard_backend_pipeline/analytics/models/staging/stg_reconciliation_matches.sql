{{
  config(
    materialized="view",
    tags=["staging", "bronze", "reconciliation"],
    description="Staging model for reconciliation match results."
  )
}}
with source as (
    select
        id as match_id,
        tenant_id,
        transaction_id,
        matched_record,
        matching_rules,
        match_score,
        match_timestamp,
        engine_version,
        status,
        processing_latency_ms,
        created_at
    from {{ ref('reconciliation_matches') }}
    where tenant_id is not null and tenant_id <> ''
)
select
    match_id,
    tenant_id,
    transaction_id,
    matched_record,
    matching_rules,
    match_score,
    match_timestamp,
    engine_version,
    status,
    processing_latency_ms,
    created_at
from source
