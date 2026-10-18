create table billing_invoice_requests (
    id uuid primary key,
    organization_id uuid not null references organizations(id) on delete cascade,
    requested_by uuid not null references users(id),
    description varchar(1000) not null,
    status varchar(16) not null default 'REQUESTED',
    reviewed_at timestamptz,
    reviewed_by uuid,
    review_reason varchar(500),
    created_at timestamptz not null default now(),
    constraint billing_invoice_requests_status_check
        check (status in ('REQUESTED', 'ISSUED', 'DECLINED'))
);

create index billing_invoice_requests_org_idx
    on billing_invoice_requests(organization_id, created_at desc);

create table billing_invoices (
    id uuid primary key,
    organization_id uuid not null references organizations(id) on delete cascade,
    invoice_request_id uuid unique references billing_invoice_requests(id),
    invoice_number varchar(48) not null unique,
    description varchar(1000) not null,
    amount_minor bigint not null,
    currency varchar(3) not null,
    status varchar(16) not null default 'OPEN',
    due_at timestamptz,
    issued_by uuid not null,
    issued_reason varchar(500) not null,
    issued_at timestamptz not null default now(),
    paid_at timestamptz,
    voided_by uuid,
    void_reason varchar(500),
    voided_at timestamptz,
    version bigint not null default 0,
    constraint billing_invoices_amount_check check (amount_minor > 0),
    constraint billing_invoices_currency_check check (currency ~ '^[A-Z]{3}$'),
    constraint billing_invoices_status_check check (status in ('OPEN', 'PAID', 'VOID'))
);

create index billing_invoices_org_idx
    on billing_invoices(organization_id, issued_at desc);

create table billing_payments (
    id uuid primary key,
    invoice_id uuid not null references billing_invoices(id),
    organization_id uuid not null references organizations(id),
    provider varchar(24) not null,
    idempotency_key varchar(128) not null,
    status varchar(24) not null,
    provider_reference varchar(255),
    checkout_url text,
    phone_last_four varchar(4),
    manual_confirmation_reason varchar(500),
    manually_confirmed_by uuid,
    cancelled_by uuid,
    cancellation_reason varchar(500),
    cancelled_at timestamptz,
    reconciled_by uuid,
    reconciliation_reason varchar(500),
    reconciled_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    paid_at timestamptz,
    version bigint not null default 0,
    constraint billing_payments_provider_check
        check (provider in ('STRIPE', 'PAYHERO', 'DARAJA', 'AIRTEL_MONEY', 'MANUAL')),
    constraint billing_payments_status_check
        check (status in ('PENDING', 'AWAITING_MANUAL', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    constraint billing_payments_org_idempotency_unique unique (organization_id, idempotency_key)
);

create index billing_payments_invoice_idx
    on billing_payments(invoice_id, created_at desc);

create unique index billing_payments_one_active_invoice_idx
    on billing_payments(invoice_id)
    where status in ('PENDING', 'AWAITING_MANUAL');

create table billing_webhook_events (
    provider varchar(24) not null,
    provider_event_id varchar(255) not null,
    payload_hash varchar(64) not null,
    received_at timestamptz not null default now(),
    processed_at timestamptz,
    primary key (provider, provider_event_id)
);
