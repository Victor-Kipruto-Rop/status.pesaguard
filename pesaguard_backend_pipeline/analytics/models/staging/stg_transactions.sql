{{
  config(
    materialized="view",
    tags=["staging", "bronze", "transactions"],
    description="Staging model for raw M-Pesa/Daraja transactions."
  )
}}
with source as (
    select
        id as transaction_id,
        trans_id as external_transaction_id,
        tenant_id,
        provider_account_id,
        provider,
        idempotency_key,
        external_reference,
        provider_transaction_id,
        trans_amount as amount,
        currency,
        status,
        lifecycle_stage,
        phone_number as msisdn,
        business_short_code,
        invoice_number,
        created_at,
        updated_at,
        version,
        metadata
    from {{ ref('transactions') }}
    where tenant_id is not null and tenant_id <> ''
),
deduped as (
    select
        *,
        row_number() over (
            partition by tenant_id, provider_account_id, external_transaction_id
            order by created_at desc
        ) as rn
    from source
)
select
    transaction_id,
    external_transaction_id,
    tenant_id,
    provider_account_id,
    provider,
    idempotency_key,
    external_reference,
    provider_transaction_id,
    amount,
    currency,
    status,
    lifecycle_stage,
    msisdn,
    business_short_code,
    invoice_number,
    created_at,
    updated_at,
    version,
    metadata
from deduped
where rn = 1
