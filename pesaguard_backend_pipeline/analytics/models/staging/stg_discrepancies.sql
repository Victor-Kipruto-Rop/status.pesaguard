{{
  config(
    materialized="view",
    tags=["staging", "bronze", "discrepancies"],
    description="Staging model for reconciliation discrepancies."
  )
}}
with source as (
    select
        id as discrepancy_id,
        trans_id as transaction_id,
        tenant_id,
        anomaly_type,
        status,
        severity,
        details,
        resolved,
        detected_at,
        resolved_at,
        resolution_note,
        latency_seconds,
        assignee,
        notes,
        timeline,
        created_at,
        updated_at
    from {{ ref('discrepancies') }}
    where tenant_id is not null and tenant_id <> ''
)
select
    discrepancy_id,
    transaction_id,
    tenant_id,
    anomaly_type,
    status,
    severity,
    details,
    resolved,
    detected_at,
    resolved_at,
    resolution_note,
    latency_seconds,
    assignee,
    notes,
    timeline,
    created_at,
    updated_at
from source
