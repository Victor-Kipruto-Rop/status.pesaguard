"""
SQLAlchemy ORM Data Models for PesaGuard's Postgres Store.

Defines schemas for Daraja transactions, idempotency tracking, discrepancy logging,
webhooks, escalation rules, on-call schedules, email audits, and dead-letter queues.
"""

from __future__ import annotations

from datetime import datetime, timezone
import uuid
from typing import Any, Dict, List, Optional

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    Column,
    DateTime,
    Float,
    ForeignKeyConstraint,
    Index,
    Integer,
    JSON,
    Numeric,
    String,
    Text,
    UniqueConstraint,
    event,
    inspect as sa_inspect,
)
from sqlalchemy.orm import declarative_base

# Registers transaction-local tenant context on all SQLAlchemy engines.
import tenant_context as _tenant_context

Base = declarative_base()


def _transaction_id_default(context):
    trans_id = str(context.get_current_parameters().get("trans_id") or "").strip()
    return trans_id


def _transaction_key_default(context):
    trans_id = _transaction_id_default(context)
    return f"transid:{trans_id.upper()}"


class Transaction(Base):
    """Raw M-Pesa transaction events received from Daraja webhooks."""

    __tablename__ = "transactions"
    __table_args__ = (
        UniqueConstraint("tenant_id", "provider_account_id", "trans_id", name="uq_transaction_scope_trans_id"),
        Index("ix_transaction_scope_trans_id", "tenant_id", "provider_account_id", "trans_id"),
        Index(
            "ix_transaction_tenant_provider_currency_created",
            "tenant_id",
            "provider",
            "currency",
            "created_at",
        ),
        Index("ix_transaction_trans_id", "trans_id"),
        Index("ix_transaction_created_at", "created_at"),
        Index("ix_transaction_msisdn", "msisdn"),
        UniqueConstraint("tenant_id", "id", name="uq_transaction_tenant_id"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_transactions_tenant_id_nonempty"),
        CheckConstraint("trans_amount > 0", name="ck_transactions_trans_amount_positive"),
        CheckConstraint("length(currency) = 3 AND currency = upper(currency)", name="ck_transactions_currency_iso"),
        CheckConstraint("status IN ('RECEIVED', 'VALIDATED', 'PROCESSING', 'RECONCILING', 'RECONCILED', 'FAILED', 'REJECTED')", name="ck_transactions_status"),
        CheckConstraint("lifecycle_stage IN ('CREATED', 'INGESTED', 'VALIDATED', 'PROCESSED', 'STORED', 'CONSUMED', 'ARCHIVED', 'DELETED', 'QUARANTINED')", name="ck_transactions_lifecycle_stage"),
        CheckConstraint("version >= 1", name="ck_transactions_version_positive"),
        UniqueConstraint("tenant_id", "provider", "provider_transaction_id", name="uq_transaction_provider_reference"),
        UniqueConstraint("tenant_id", "provider", "external_reference", name="uq_transaction_external_reference"),
    )

    id = Column(String, primary_key=True, default=lambda: f"txn_{uuid.uuid4().hex}")
    trans_id = Column(String, nullable=False)
    tenant_id = Column(String, nullable=False)
    provider_account_id = Column(String, nullable=False, default="legacy-default", server_default="legacy-default")
    provider = Column(String(64), nullable=False, default="mpesa", server_default="mpesa")
    idempotency_key = Column(String(255), nullable=False, default=_transaction_key_default)
    external_reference = Column(String(255), nullable=True)
    provider_transaction_id = Column(String(255), nullable=False, default=_transaction_id_default)
    trans_amount = Column(Numeric(19, 3), nullable=False)
    currency = Column(String(3), nullable=False, default="KES", server_default="KES")
    msisdn = Column(String, nullable=False)
    business_short_code = Column(String, nullable=False)
    trans_time = Column(String, nullable=False)  # Raw string timestamp format from Daraja
    raw_payload = Column(JSON, nullable=False)
    raw_payload_hash = Column(String(64), nullable=True)
    source_schema_version = Column(String(32), nullable=False, default="1.0", server_default="1.0")
    status = Column(String(32), nullable=False, default="RECEIVED", server_default="RECEIVED")
    lifecycle_stage = Column(String(16), nullable=False, default="STORED", server_default="STORED")
    archived_at = Column(DateTime(timezone=True), nullable=True)
    archive_object_key = Column(String(1024), nullable=True)
    version = Column(Integer, nullable=False, default=1, server_default="1")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)

    __mapper_args__ = {"version_id_col": version}


@event.listens_for(Transaction, "before_update")
def _prevent_transaction_source_mutation(_mapper, _connection, transaction):
    state = sa_inspect(transaction)
    changed = {
        name
        for name in (
            "raw_payload",
            "trans_id",
            "tenant_id",
            "provider_account_id",
            "provider",
            "idempotency_key",
            "external_reference",
            "provider_transaction_id",
            "trans_amount",
            "currency",
            "msisdn",
            "business_short_code",
            "trans_time",
            "raw_payload_hash",
            "source_schema_version",
        )
        if state.attrs[name].history.has_changes()
    }
    raw_changed = "raw_payload" in changed
    raw_payload = transaction.raw_payload
    is_privacy_redaction = (
        raw_changed
        and isinstance(raw_payload, dict)
        and raw_payload.get("redacted") is True
    )
    immutable_identity = changed - {"raw_payload", "msisdn"}
    if immutable_identity:
        raise ValueError("transaction source fields are immutable")
    if "msisdn" in changed and not is_privacy_redaction:
        raise ValueError("transaction source fields are immutable")
    if raw_changed and not is_privacy_redaction:
        raise ValueError("transaction raw payload is immutable")


class TransformationRecord(Base):
    """Append-only evidence for each immutable transaction data stage."""

    __tablename__ = "transformation_records"
    __table_args__ = (
        UniqueConstraint("tenant_id", "event_id", "stage", name="uq_transformation_stage_event"),
        Index("ix_transformation_tenant_transaction", "tenant_id", "transaction_id", "created_at"),
        Index("ix_transformation_tenant_stage", "tenant_id", "stage", "created_at"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_transformation_tenant_nonempty"),
        CheckConstraint("stage IN ('RAW', 'VALIDATED', 'NORMALIZED', 'ENRICHED', 'PROCESSED')", name="ck_transformation_stage"),
    )

    id = Column(String, primary_key=True, default=lambda: f"stage_{uuid.uuid4().hex}")
    tenant_id = Column(String, nullable=False)
    transaction_id = Column(String, nullable=False)
    event_id = Column(String, nullable=False)
    source_event_id = Column(String, nullable=True)
    stage = Column(String(16), nullable=False)
    payload = Column(JSON, nullable=False)
    payload_hash = Column(String(64), nullable=False)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class MerchantMetric(Base):
    """Persisted derived merchant metrics for dashboard and reporting reads."""

    __tablename__ = "merchant_metrics"
    __table_args__ = (
        UniqueConstraint("tenant_id", "merchant_id", "currency", "granularity", "bucket_start", name="uq_merchant_metric_bucket_currency"),
        Index("ix_merchant_metrics_tenant_bucket", "tenant_id", "granularity", "bucket_start"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_merchant_metrics_tenant_nonempty"),
        CheckConstraint("length(currency) = 3 AND currency = upper(currency)", name="ck_merchant_metrics_currency_iso"),
        CheckConstraint("granularity IN ('hour', 'day', 'week')", name="ck_merchant_metrics_granularity"),
        CheckConstraint("transaction_count >= 0", name="ck_merchant_metrics_count_nonnegative"),
        CheckConstraint("failed_transactions >= 0", name="ck_merchant_metrics_failed_nonnegative"),
        CheckConstraint("reconciled_transactions >= 0", name="ck_merchant_metrics_reconciled_nonnegative"),
        CheckConstraint("anomaly_count >= 0", name="ck_merchant_metrics_anomaly_nonnegative"),
    )

    id = Column(String, primary_key=True, default=lambda: f"metric_{uuid.uuid4().hex}")
    tenant_id = Column(String, nullable=False)
    merchant_id = Column(String, nullable=False)
    currency = Column(String(3), nullable=False, default="KES", server_default="KES")
    granularity = Column(String(8), nullable=False)
    bucket_start = Column(DateTime(timezone=True), nullable=False)
    transaction_count = Column(Integer, nullable=False, default=0, server_default="0")
    total_volume = Column(Numeric(21, 3), nullable=False, default=0, server_default="0")
    average_transaction = Column(Numeric(21, 3), nullable=False, default=0, server_default="0")
    failed_transactions = Column(Integer, nullable=False, default=0, server_default="0")
    reconciled_transactions = Column(Integer, nullable=False, default=0, server_default="0")
    anomaly_count = Column(Integer, nullable=False, default=0, server_default="0")
    updated_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class DeletionRequest(Base):
    """Durable approval and execution state for controlled data deletion."""

    __tablename__ = "deletion_requests"
    __table_args__ = (
        Index("ix_deletion_requests_tenant_status", "tenant_id", "status", "created_at"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_deletion_request_tenant_nonempty"),
        CheckConstraint("status IN ('pending', 'approved', 'anonymized', 'deleted', 'rejected', 'failed')", name="ck_deletion_request_status"),
    )

    id = Column(String, primary_key=True, default=lambda: f"del_{uuid.uuid4().hex}")
    tenant_id = Column(String, nullable=False)
    transaction_id = Column(String, nullable=False)
    requested_by = Column(String, nullable=False)
    reason = Column(Text, nullable=False)
    status = Column(String(16), nullable=False, default="pending", server_default="pending")
    decision = Column(String(32), nullable=True)
    dependency_report = Column(JSON, nullable=False, default=dict, server_default="{}")
    authorization_reference = Column(String(255), nullable=True)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    completed_at = Column(DateTime(timezone=True), nullable=True)


class LineageRecord(Base):
    """Append-only record connecting a served result to its source event."""

    __tablename__ = "lineage_records"
    __table_args__ = (
        UniqueConstraint("tenant_id", "event_id", "stage", name="uq_lineage_event_stage"),
        Index("ix_lineage_tenant_transaction", "tenant_id", "transaction_id", "created_at"),
        Index("ix_lineage_tenant_source_event", "tenant_id", "source_event_id"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_lineage_tenant_nonempty"),
    )

    id = Column(String, primary_key=True, default=lambda: f"lineage_{uuid.uuid4().hex}")
    tenant_id = Column(String, nullable=False)
    transaction_id = Column(String, nullable=False)
    event_id = Column(String, nullable=False)
    stage = Column(String(32), nullable=False)
    source = Column(String(64), nullable=False)
    source_event_id = Column(String(255), nullable=True)
    upstream_event_id = Column(String(255), nullable=True)
    ingestion_time = Column(DateTime(timezone=True), nullable=False)
    pipeline_version = Column(String(64), nullable=False)
    transformation_version = Column(String(64), nullable=False)
    job_id = Column(String(255), nullable=True)
    correlation_id = Column(String(255), nullable=True)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class IdempotencyRecord(Base):
    """Durable request identity ledger shared by API and provider ingestion."""

    __tablename__ = "idempotency_records"
    __table_args__ = (
        UniqueConstraint("tenant_id", "provider", "idempotency_key", name="uq_idempotency_request"),
        UniqueConstraint("tenant_id", "provider", "provider_transaction_id", name="uq_idempotency_provider_reference"),
        Index("ix_idempotency_tenant_key", "tenant_id", "idempotency_key"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_idempotency_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False)
    provider = Column(String(64), nullable=False)
    idempotency_key = Column(String(255), nullable=False)
    external_reference = Column(String(255), nullable=True)
    provider_transaction_id = Column(String(255), nullable=False)
    request_hash = Column(String(64), nullable=False)
    response = Column(JSON, nullable=True)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class AuditEvent(Base):
    """Append-only business audit event ledger."""

    __tablename__ = "audit_events"
    __table_args__ = (
        UniqueConstraint("tenant_id", "event_key", name="uq_audit_events_tenant_key"),
        Index("ix_audit_events_tenant_created", "tenant_id", "created_at"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_audit_events_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False)
    event_key = Column(String, nullable=False)
    event_type = Column(String, nullable=False)
    aggregate_type = Column(String, nullable=False)
    aggregate_id = Column(String, nullable=False)
    actor = Column(String, nullable=False)
    payload_hash = Column(String(64), nullable=False)
    details = Column(JSON, nullable=False, default=dict, server_default="{}")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class ReconciliationMatch(Base):
    """Durable, queryable evidence for one reconciliation decision."""

    __tablename__ = "reconciliation_matches"
    __table_args__ = (
        UniqueConstraint("tenant_id", "transaction_id", name="uq_reconciliation_matches_transaction"),
        Index("ix_reconciliation_matches_tenant_status", "tenant_id", "status", "created_at"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_reconciliation_matches_tenant_nonempty"),
        CheckConstraint("status IN ('MATCHED', 'UNMATCHED', 'PARTIAL', 'MISMATCH', 'DUPLICATE', 'PENDING', 'EXCEPTION')", name="ck_reconciliation_matches_status"),
        CheckConstraint("match_score >= 0 AND match_score <= 1", name="ck_reconciliation_matches_score"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False)
    transaction_id = Column(String, nullable=False)
    matched_record = Column(JSON, nullable=True)
    matching_rules = Column(JSON, nullable=False, default=list, server_default="[]")
    match_score = Column(Numeric(5, 4), nullable=False)
    match_timestamp = Column(DateTime(timezone=True), nullable=False)
    engine_version = Column(String(32), nullable=False)
    status = Column(String(32), nullable=False)
    processing_latency_ms = Column(Numeric(12, 3), nullable=False)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class CustomerAccount(Base):
    """One account per derived customer x currency; balance = sum of immutable transactions."""

    __tablename__ = "customer_accounts"
    __table_args__ = (
        UniqueConstraint("tenant_id", "customer_key", "currency", name="uq_customer_accounts_tenant_key_currency"),
        Index("ix_customer_accounts_tenant_key", "tenant_id", "customer_key"),
        Index("ix_customer_accounts_tenant_currency", "tenant_id", "currency"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_customer_accounts_tenant_id_nonempty"),
        CheckConstraint("length(currency) = 3 AND currency = upper(currency)", name="ck_customer_accounts_currency_iso"),
        CheckConstraint("status IN ('active', 'suspended', 'closed')", name="ck_customer_accounts_status"),
    )

    id = Column(String, primary_key=True, default=lambda: f"acct_{uuid.uuid4().hex}")
    tenant_id = Column(String, nullable=False)
    customer_key = Column(String(128), nullable=False)
    currency = Column(String(3), nullable=False, server_default="KES")
    status = Column(String(16), nullable=False, server_default="active")
    total = Column(Numeric(19, 3), nullable=False, server_default="0")
    transaction_count = Column(Integer, nullable=False, server_default="0")
    first_seen = Column(DateTime(timezone=True), nullable=False, default=lambda: datetime.now(timezone.utc))
    last_seen = Column(DateTime(timezone=True), nullable=False, default=lambda: datetime.now(timezone.utc))
    updated_at = Column(
        DateTime(timezone=True),
        nullable=False,
        default=lambda: datetime.now(timezone.utc),
        onupdate=lambda: datetime.now(timezone.utc),
    )
    internal_ref = Column(String(255), nullable=False)


class ReconciliationGroundTruth(Base):
    """Validated expected outcomes used to calculate certification metrics."""

    __tablename__ = "reconciliation_ground_truth"
    __table_args__ = (
        UniqueConstraint("tenant_id", "transaction_id", name="uq_reconciliation_ground_truth_transaction"),
        Index("ix_reconciliation_ground_truth_validation", "tenant_id", "validated_at"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_reconciliation_ground_truth_tenant_nonempty"),
        CheckConstraint("expected_status IN ('MATCHED', 'UNMATCHED', 'PARTIAL', 'MISMATCH', 'DUPLICATE', 'PENDING', 'EXCEPTION')", name="ck_ground_truth_expected_status"),
        CheckConstraint("actual_status IS NULL OR actual_status IN ('MATCHED', 'UNMATCHED', 'PARTIAL', 'MISMATCH', 'DUPLICATE', 'PENDING', 'EXCEPTION')", name="ck_ground_truth_actual_status"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False)
    transaction_id = Column(String, nullable=False)
    expected_status = Column(String(32), nullable=False)
    actual_status = Column(String(32), nullable=True)
    source = Column(String(64), nullable=False, default="certification", server_default="certification")
    approved_by = Column(String(128), nullable=False)
    approval_reference = Column(String(255), nullable=False)
    approved_at = Column(DateTime(timezone=True), nullable=False)
    validated_by = Column(String(128), nullable=True)
    validated_at = Column(DateTime(timezone=True), nullable=True)
    notes = Column(Text, nullable=True)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class Customer(Base):
    """Derived customer identity keyed by a tokenized MSISDN (never raw phone data)."""

    __tablename__ = "customers"
    __table_args__ = (
        UniqueConstraint("tenant_id", "customer_key", name="uq_customers_tenant_key"),
        Index("ix_customers_tenant_status", "tenant_id", "status"),
        Index("ix_customers_tenant_key", "tenant_id", "customer_key"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_customers_tenant_id_nonempty"),
        CheckConstraint("status IN ('active', 'suspended', 'closed')", name="ck_customers_status"),
    )

    id = Column(String, primary_key=True, default=lambda: f"cust_{uuid.uuid4().hex}")
    tenant_id = Column(String, nullable=False)
    customer_key = Column(String(128), nullable=False)
    internal_ref = Column(String(255), nullable=False)
    phone_number = Column(String(16), nullable=False)
    label = Column(String(64), nullable=False)
    status = Column(String(16), nullable=False, server_default="active")
    total_volumes = Column(JSON, nullable=False, server_default="{}")
    first_seen = Column(DateTime(timezone=True), nullable=False, default=lambda: datetime.now(timezone.utc))
    last_seen = Column(DateTime(timezone=True), nullable=False, default=lambda: datetime.now(timezone.utc))
    transaction_count = Column(Integer, nullable=False, server_default="0")
    total_amount = Column(Numeric(19, 3), nullable=False, server_default="0")
    updated_at = Column(
        DateTime(timezone=True),
        nullable=False,
        default=lambda: datetime.now(timezone.utc),
        onupdate=lambda: datetime.now(timezone.utc),
    )


class AccountBalance(Base):
    """Recomputed zero-decimal balance snapshot per customer x currency.

    This is a derived read model: the value is computed from the immutable
    ``transactions`` ledger on demand, never maintained independently, so the
    stored total is always provably equal to the source sum.
    """

    __tablename__ = "account_balances"
    __table_args__ = (
        UniqueConstraint("tenant_id", "customer_key", "currency", name="uq_account_balances_tenant_key_currency"),
        Index("ix_account_balances_tenant_currency", "tenant_id", "currency"),
        Index("ix_account_balances_tenant_key", "tenant_id", "customer_key"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_account_balances_tenant_id_nonempty"),
        CheckConstraint("length(currency) = 3 AND currency = upper(currency)", name="ck_account_balances_currency_iso"),
        CheckConstraint("total >= 0", name="ck_account_balances_total_nonnegative"),
        CheckConstraint("transaction_count >= 0", name="ck_account_balances_count_nonnegative"),
    )

    id = Column(String, primary_key=True, default=lambda: f"bal_{uuid.uuid4().hex}")
    tenant_id = Column(String, nullable=False)
    customer_key = Column(String(128), nullable=False)
    currency = Column(String(3), nullable=False, server_default="KES")
    total = Column(Numeric(19, 2), nullable=False, server_default="0")
    transaction_count = Column(Integer, nullable=False, server_default="0")
    computed_at = Column(
        DateTime(timezone=True),
        nullable=False,
        default=lambda: datetime.now(timezone.utc),
    )


class AccountRelationship(Base):
    """Observed customer <-> merchant relationship derived from reconciled pairs."""

    __tablename__ = "account_relationships"
    __table_args__ = (
        UniqueConstraint("tenant_id", "customer_key", "merchant_id", name="uq_account_relationships_tenant_customer_merchant"),
        Index("ix_account_relationships_tenant_customer", "tenant_id", "customer_key"),
        Index("ix_account_relationships_tenant_merchant", "tenant_id", "merchant_id"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_account_relationships_tenant_id_nonempty"),
        CheckConstraint("length(merchant_id) > 0", name="ck_account_relationships_merchant_nonempty"),
        CheckConstraint("status IN ('active', 'closed')", name="ck_account_relationships_status"),
    )

    id = Column(String, primary_key=True, default=lambda: f"rel_{uuid.uuid4().hex}")
    tenant_id = Column(String, nullable=False)
    customer_key = Column(String(128), nullable=False)
    merchant_id = Column(String(255), nullable=False)
    status = Column(String(16), nullable=False, server_default="active")
    first_seen = Column(DateTime(timezone=True), nullable=False, default=lambda: datetime.now(timezone.utc))
    last_seen = Column(DateTime(timezone=True), nullable=False, default=lambda: datetime.now(timezone.utc))
    pair_count = Column(Integer, nullable=False, server_default="0")
    updated_at = Column(
        DateTime(timezone=True),
        nullable=False,
        default=lambda: datetime.now(timezone.utc),
        onupdate=lambda: datetime.now(timezone.utc),
    )

class TransactionEvent(Base):
    """Append-only source and lifecycle history for a financial transaction."""

    __tablename__ = "transaction_events"
    __table_args__ = (
        UniqueConstraint("tenant_id", "event_key", name="uq_transaction_events_tenant_key"),
        Index("ix_transaction_events_transaction", "tenant_id", "transaction_id", "created_at"),
        Index("ix_transaction_events_correlation", "tenant_id", "correlation_id"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_transaction_events_tenant_id_nonempty"),
        ForeignKeyConstraint(
            ["tenant_id", "transaction_id"],
            ["transactions.tenant_id", "transactions.id"],
            name="fk_transaction_events_transaction_id",
        ),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False)
    transaction_id = Column(String, nullable=True)
    trans_id = Column(String, nullable=False)
    event_key = Column(String, nullable=False)
    event_type = Column(String, nullable=False)
    from_state = Column(String, nullable=True)
    to_state = Column(String, nullable=False)
    actor = Column(String, nullable=False)
    reason = Column(Text, nullable=True)
    payload_hash = Column(String(64), nullable=True)
    correlation_id = Column(String, nullable=True)
    details = Column(JSON, nullable=False, default=dict, server_default="{}")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class ProcessedTransaction(Base):
    """
    Explicit Idempotency Ledger: Tracks which webhook callbacks have been received.
    
    Prevents race conditions and duplicate transaction handling using a unique constraint
    on daraja_trans_id.
    """

    __tablename__ = "processed_transactions"
    __table_args__ = (
        UniqueConstraint("tenant_id", "provider_account_id", "daraja_trans_id", name="uq_processed_scope_trans_id"),
        Index("ix_processed_daraja_id", "daraja_trans_id"),
        Index("ix_processed_scope", "tenant_id", "provider_account_id"),
        Index("ix_processed_received_at", "received_at"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_processed_tenant_id_nonempty"),
        CheckConstraint("status IN ('received', 'validated', 'stored', 'failed')", name="ck_processed_status"),
        CheckConstraint("reconciliation_status IN ('pending', 'processing', 'completed', 'failed')", name="ck_processed_reconciliation_status"),
    )

    id = Column(String, primary_key=True)
    daraja_trans_id = Column(String, nullable=False)
    tenant_id = Column(String, nullable=False)
    provider_account_id = Column(String, nullable=False, default="legacy-default", server_default="legacy-default")
    status = Column(String, nullable=False, default="received")  # received, validated, stored, failed
    processing_time_ms = Column(Integer, nullable=True)
    received_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    webhook_attempt_number = Column(Integer, default=1)
    source_ip = Column(String, nullable=True)
    signature_verified = Column(Boolean, default=False)
    error_reason = Column(String, nullable=True)
    reconciliation_status = Column(String, nullable=False, default="pending", server_default="pending")
    reconciliation_attempts = Column(Integer, nullable=False, default=0, server_default="0")
    reconciliation_started_at = Column(DateTime(timezone=True), nullable=True)
    reconciliation_completed_at = Column(DateTime(timezone=True), nullable=True)
    reconciliation_error = Column(Text, nullable=True)


class FraudRiskAssessment(Base):
    """Explainable fraud decision persisted independently from reconciliation state."""

    __tablename__ = "fraud_risk_assessments"
    __table_args__ = (
        UniqueConstraint("tenant_id", "transaction_id", name="uq_fraud_risk_tenant_transaction"),
        Index("ix_fraud_risk_tenant_level", "tenant_id", "risk_level", "created_at"),
        CheckConstraint("risk_score >= 0 AND risk_score <= 1", name="ck_fraud_risk_score_range"),
        CheckConstraint("risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')", name="ck_fraud_risk_level"),
    )

    id = Column(String, primary_key=True, default=lambda: f"fraud_{uuid.uuid4().hex}")
    tenant_id = Column(String, nullable=False)
    transaction_id = Column(String, nullable=False)
    risk_score = Column(Numeric(6, 4), nullable=False)
    risk_level = Column(String(16), nullable=False)
    action = Column(String(16), nullable=False)
    reason_codes = Column(JSON, nullable=False, default=list, server_default="[]")
    model_version = Column(String(64), nullable=False)
    rules_triggered = Column(JSON, nullable=False, default=list, server_default="[]")
    features = Column(JSON, nullable=False, default=dict, server_default="{}")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class TransactionOutbox(Base):
    """Durable downstream publication intent for an accepted transaction."""

    __tablename__ = "transaction_outbox"
    __table_args__ = (
        UniqueConstraint("tenant_id", "event_key", name="uq_transaction_outbox_tenant_event"),
        Index("ix_transaction_outbox_pending", "status", "available_at", "created_at"),
        Index("ix_transaction_outbox_tenant_created", "tenant_id", "created_at"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_transaction_outbox_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False, default="default", server_default="default")
    event_key = Column(String, nullable=False)
    topic = Column(String, nullable=False)
    payload = Column(JSON, nullable=False)
    status = Column(String, nullable=False, default="pending", server_default="pending")
    attempts = Column(Integer, nullable=False, default=0, server_default="0")
    available_at = Column(DateTime(timezone=True), nullable=False, default=lambda: datetime.now(timezone.utc))
    locked_until = Column(DateTime(timezone=True), nullable=True)
    last_error = Column(Text, nullable=True)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    published_at = Column(DateTime(timezone=True), nullable=True)


class ImportJob(Base):
    """Durable lifecycle and counters for one tenant-scoped file import."""

    __tablename__ = "import_jobs"
    __table_args__ = (
        Index("ix_import_jobs_tenant_status", "tenant_id", "status", "created_at"),
        Index("ix_import_jobs_status_created", "status", "created_at"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_import_jobs_tenant_id_nonempty"),
        CheckConstraint("status IN ('queued', 'running', 'completed', 'failed')", name="ck_import_jobs_status"),
        CheckConstraint("records_received >= 0", name="ck_import_jobs_received_nonnegative"),
        CheckConstraint("records_valid >= 0", name="ck_import_jobs_valid_nonnegative"),
        CheckConstraint("records_failed >= 0", name="ck_import_jobs_failed_nonnegative"),
        CheckConstraint("attempts >= 0", name="ck_import_jobs_attempts_nonnegative"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False)
    source = Column(String(64), nullable=False)
    filename = Column(String(255), nullable=False)
    file_format = Column(String(16), nullable=False)
    object_key = Column(String(512), nullable=False, unique=True)
    records_received = Column(Integer, nullable=False, default=0, server_default="0")
    records_valid = Column(Integer, nullable=False, default=0, server_default="0")
    records_failed = Column(Integer, nullable=False, default=0, server_default="0")
    attempts = Column(Integer, nullable=False, default=0, server_default="0")
    started_at = Column(DateTime(timezone=True), nullable=True)
    completed_at = Column(DateTime(timezone=True), nullable=True)
    lease_expires_at = Column(DateTime(timezone=True), nullable=True)
    status = Column(String(16), nullable=False, default="queued", server_default="queued")
    error_summary = Column(JSON, nullable=False, default=list, server_default="[]")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class ReconciliationOutbox(Base):
    """Durable publication intent for matched/discrepancy outcomes."""

    __tablename__ = "reconciliation_outbox"
    __table_args__ = (
        UniqueConstraint("tenant_id", "event_key", name="uq_reconciliation_outbox_tenant_event"),
        Index("ix_reconciliation_outbox_pending", "status", "available_at", "created_at"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_reconciliation_outbox_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False, default="default", server_default="default")
    event_key = Column(String, nullable=False)
    topic = Column(String, nullable=False)
    payload = Column(JSON, nullable=False)
    status = Column(String, nullable=False, default="pending", server_default="pending")
    attempts = Column(Integer, nullable=False, default=0, server_default="0")
    available_at = Column(DateTime(timezone=True), nullable=False, default=lambda: datetime.now(timezone.utc))
    locked_until = Column(DateTime(timezone=True), nullable=True)
    last_error = Column(Text, nullable=True)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    published_at = Column(DateTime(timezone=True), nullable=True)


class Discrepancy(Base):
    """Reconciliation anomaly records flagged during transaction checks."""

    __tablename__ = "discrepancies"
    __table_args__ = (
        UniqueConstraint("tenant_id", "id", name="uq_discrepancy_tenant_id"),
        Index("ix_discrepancy_trans_id", "trans_id"),
        Index("ix_discrepancy_tenant_id", "tenant_id"),
        Index("ix_discrepancy_detected_at", "detected_at"),
        Index("ix_discrepancy_status_resolved", "status", "resolved"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_discrepancy_tenant_id_nonempty"),
        CheckConstraint("status IN ('needs_review', 'reviewed', 'resolved', 'escalated')", name="ck_discrepancy_status"),
        CheckConstraint("severity IN ('info', 'warning', 'critical')", name="ck_discrepancy_severity"),
    )

    id = Column(String, primary_key=True)  # Format: f"{trans_id}-{anomaly_type}"
    trans_id = Column(String, nullable=False)
    tenant_id = Column(String, nullable=False, default="default", server_default="default")
    anomaly_type = Column(String, nullable=False)
    status = Column(String, nullable=False, default="needs_review")
    severity = Column(String, nullable=False, default="warning")
    details = Column(Text, nullable=True)
    resolved = Column(Boolean, default=False)
    detected_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    resolved_at = Column(DateTime(timezone=True), nullable=True)
    resolution_note = Column(Text, nullable=True)
    latency_seconds = Column(Integer, nullable=True)
    assignee = Column(String, nullable=True)
    notes = Column(Text, nullable=True)
    timeline = Column(JSON, nullable=True, default=list)


class DiscrepancyEvent(Base):
    """Append-only discrepancy lifecycle event history."""

    __tablename__ = "discrepancy_events"
    __table_args__ = (
        UniqueConstraint("tenant_id", "event_key", name="uq_discrepancy_events_tenant_key"),
        Index("ix_discrepancy_events_scope", "tenant_id", "discrepancy_id", "created_at"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_discrepancy_events_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False)
    discrepancy_id = Column(String, nullable=False)
    event_key = Column(String, nullable=False)
    from_state = Column(String, nullable=True)
    to_state = Column(String, nullable=False)
    actor = Column(String, nullable=False)
    reason = Column(Text, nullable=True)
    correlation_id = Column(String, nullable=True)
    details = Column(JSON, nullable=False, default=dict, server_default="{}")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class InternalRecord(Base):
    """Customer internal ledger or order system record baseline for comparison."""

    __tablename__ = "internal_records"
    __table_args__ = (
        Index("ix_internal_records_tenant_phone", "tenant_id", "phone_number"),
        Index("ix_internal_records_phone", "phone_number"),
        Index("ix_internal_records_synced", "synced_at"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_internal_records_tenant_id_nonempty"),
        CheckConstraint("length(currency) = 3 AND currency = upper(currency)", name="ck_internal_records_currency_iso"),
    )

    internal_ref = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False, default="default", server_default="default")
    amount = Column(Numeric(19, 3), nullable=False)
    currency = Column(String(3), nullable=False, default="KES", server_default="KES")
    phone_number = Column(String, nullable=False)
    status = Column(String, nullable=False)
    synced_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class WebhookConfig(Base):
    """Registered customer outbound webhook endpoint configurations."""

    __tablename__ = "webhook_configs"
    __table_args__ = (
        Index("ix_webhook_configs_tenant", "tenant_id"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_webhook_configs_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False)
    url = Column(String, nullable=False)
    event_types = Column(JSON, nullable=False)  # e.g., ["escalation", "resolution"]
    active = Column(Boolean, default=True)
    retry_attempts = Column(Integer, default=3)
    timeout_seconds = Column(Integer, default=10)
    signing_secret = Column(String, nullable=True)  # Dynamic HMAC signing key
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    updated_at = Column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        onupdate=lambda: datetime.now(timezone.utc),
        nullable=False,
    )


class WebhookDelivery(Base):
    """Outbound webhook delivery execution and response audit logs."""

    __tablename__ = "webhook_deliveries"
    __table_args__ = (
        Index("ix_webhook_deliveries_webhook_id", "webhook_id"),
        Index("ix_webhook_deliveries_tenant_webhook", "tenant_id", "webhook_id"),
        Index("ix_webhook_deliveries_created_at", "created_at"),
        Index("ix_webhook_deliveries_recovery", "status", "updated_at"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_webhook_deliveries_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False, default="default", server_default="default")
    webhook_id = Column(String, nullable=False)
    event_type = Column(String, nullable=False)
    payload = Column(JSON, nullable=False)
    status = Column(String, nullable=False)  # success, failed, pending, processing
    response_status = Column(Integer, nullable=True)
    response_body = Column(Text, nullable=True)
    attempt_count = Column(Integer, default=0)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    updated_at = Column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        onupdate=lambda: datetime.now(timezone.utc),
        nullable=False,
    )
    delivered_at = Column(DateTime(timezone=True), nullable=True)


class EscalationRule(Base):
    """Per-tenant automated escalation criteria and notification triggers."""

    __tablename__ = "escalation_rules"
    __table_args__ = (
        Index("ix_escalation_rules_tenant", "tenant_id", "priority"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_escalation_rules_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False)
    name = Column(String, nullable=False)
    description = Column(Text, nullable=True)
    condition_field = Column(String, nullable=False)  # e.g., "severity", "anomaly_type"
    condition_operator = Column(String, nullable=False)  # e.g., "equals", "greater_than"
    condition_value = Column(String, nullable=False)
    action = Column(String, nullable=False)  # "escalate", "notify", "webhook"
    target = Column(String, nullable=True)
    webhook_url = Column(String, nullable=True)
    active = Column(Boolean, default=True)
    priority = Column(Integer, default=0)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class OnCallRotation(Base):
    """On-call operational shift rotation schedules."""

    __tablename__ = "on_call_rotations"
    __table_args__ = (
        Index("ix_on_call_tenant_shift", "tenant_id", "shift_start", "shift_end"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_on_call_rotations_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False)
    operator_id = Column(String, nullable=False)
    operator_name = Column(String, nullable=True)
    operator_email = Column(String, nullable=True)
    operator_phone = Column(String, nullable=True)
    shift_start = Column(DateTime(timezone=True), nullable=False)
    shift_end = Column(DateTime(timezone=True), nullable=False)
    is_active = Column(Boolean, default=False)
    escalation_level = Column(Integer, default=1)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class EmailNotification(Base):
    """Audit log for outgoing email alerts and reconciliation reports."""

    __tablename__ = "email_notifications"
    __table_args__ = (
        Index("ix_email_tenant_created", "tenant_id", "created_at"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_email_notifications_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False)
    recipient_email = Column(String, nullable=False)
    report_type = Column(String, nullable=False)  # "reconciliation", "daily_summary", "escalation"
    subject = Column(String, nullable=False)
    status = Column(String, nullable=False)  # "pending", "sent", "failed"
    content_hash = Column(String, nullable=True)
    error_message = Column(Text, nullable=True)
    sent_at = Column(DateTime(timezone=True), nullable=True)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class PublicStatusSubscription(Base):
    """Double-opt-in email subscriptions for public service-status changes."""

    __tablename__ = "public_status_subscriptions"
    __table_args__ = (
        UniqueConstraint("email_hash", name="uq_public_status_subscription_email_hash"),
        Index("ix_public_status_subscription_confirmed", "confirmed"),
    )

    id = Column(String(32), primary_key=True)
    email = Column(String(254), nullable=False)
    email_hash = Column(String(64), nullable=False)
    confirmation_token_hash = Column(String(64), nullable=True)
    confirmation_expires_at = Column(DateTime(timezone=True), nullable=True)
    confirmed = Column(Boolean, nullable=False, default=False, server_default="false")
    last_status_fingerprint = Column(String(64), nullable=True)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    confirmed_at = Column(DateTime(timezone=True), nullable=True)


class DeadLetter(Base):
    """Failed, malformed, or unprocessable webhook payload repository."""

    __tablename__ = "dead_letters"
    __table_args__ = (
        Index("ix_dead_letters_tenant", "tenant_id"),
        Index("ix_dead_letters_created", "created_at"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_dead_letters_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False, default="default", server_default="default")
    reason = Column(String, nullable=False)
    payload = Column(JSON, nullable=True)
    error_detail = Column(Text, nullable=True)
    attempts = Column(Integer, default=0)
    processed = Column(Boolean, default=False)
    processed_at = Column(DateTime(timezone=True), nullable=True)
    replay_status = Column(String, nullable=False, default="idle", server_default="idle")
    replayed_by = Column(String, nullable=True)
    replayed_at = Column(DateTime(timezone=True), nullable=True)
    replay_reason = Column(Text, nullable=True)
    provider_account_id = Column(String, nullable=True)
    event_key = Column(String, nullable=True, index=True)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    received_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class Report(Base):
    """Persisted daily, weekly, or ad-hoc reconciliation reports."""

    __tablename__ = "reports"
    __table_args__ = (
        Index("ix_reports_tenant_type", "tenant_id", "report_type"),
        Index("ix_reports_created", "created_at"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_reports_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False)
    report_type = Column(String, nullable=False)  # "daily", "weekly", "monthly"
    period_start = Column(DateTime(timezone=True), nullable=False)
    period_end = Column(DateTime(timezone=True), nullable=False)
    content = Column(JSON, nullable=True)
    status = Column(String, nullable=False, default="generated")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    delivered_at = Column(DateTime(timezone=True), nullable=True)


class UserAccount(Base):
    """Local account record provisioned from an external IdP or internal directory."""

    __tablename__ = "user_accounts"
    __table_args__ = (
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_user_accounts_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False, default="default")
    username = Column(String, nullable=False)
    email = Column(String, nullable=True)
    password_hash = Column(String, nullable=True)
    password_salt = Column(String, nullable=True)
    roles = Column(JSON, nullable=False, default=list)
    permissions = Column(JSON, nullable=False, default=list)
    attributes = Column(JSON, nullable=True, default=dict)
    mfa_enabled = Column(Boolean, nullable=False, default=False)
    status = Column(String, nullable=False, default="active")
    authorization_version = Column(Integer, nullable=False, default=1)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    updated_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc), nullable=False)


class UserIdentity(Base):
    """Canonical human identity record, separate from credentials and sessions."""

    __tablename__ = "iam_user_identities"
    __table_args__ = (
        UniqueConstraint("tenant_id", "external_id", name="uq_iam_user_identity_external_id"),
        UniqueConstraint("tenant_id", "email", name="uq_iam_user_identity_email"),
        Index("ix_iam_user_identity_tenant_status", "tenant_id", "status"),
        CheckConstraint(
            "status IN ('INVITED', 'PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'LOCKED', 'DEACTIVATED', 'DELETED')",
            name="ck_iam_user_identity_status",
        ),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_iam_user_identity_tenant_nonempty"),
    )

    user_id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False)
    external_id = Column(String(255), nullable=False)
    email = Column(String, nullable=True)
    email_verified = Column(Boolean, nullable=False, default=False, server_default="false")
    email_verified_at = Column(DateTime(timezone=True), nullable=True)
    phone = Column(String(64), nullable=True)
    phone_verified = Column(Boolean, nullable=False, default=False, server_default="false")
    phone_verified_at = Column(DateTime(timezone=True), nullable=True)
    first_name = Column(String(128), nullable=True)
    last_name = Column(String(128), nullable=True)
    display_name = Column(String(255), nullable=True)
    status = Column(String(32), nullable=False, default="PENDING_VERIFICATION", server_default="PENDING_VERIFICATION")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    updated_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc), nullable=False)
    last_login_at = Column(DateTime(timezone=True), nullable=True)
    last_activity_at = Column(DateTime(timezone=True), nullable=True)
    deactivated_at = Column(DateTime(timezone=True), nullable=True)
    deleted_at = Column(DateTime(timezone=True), nullable=True)


class PasswordCredential(Base):
    """Versioned password credential; authentication state is not identity data."""

    __tablename__ = "iam_password_credentials"
    __table_args__ = (
        Index("ix_iam_password_credential_user_status", "tenant_id", "user_id", "status"),
        CheckConstraint("status IN ('ACTIVE', 'REVOKED', 'EXPIRED', 'COMPROMISED')", name="ck_iam_password_credential_status"),
        CheckConstraint("algorithm = 'argon2id'", name="ck_iam_password_credential_algorithm"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_iam_password_credential_tenant_nonempty"),
    )

    credential_id = Column(String, primary_key=True)
    user_id = Column(String, nullable=False)
    tenant_id = Column(String, nullable=False)
    password_hash = Column(Text, nullable=False)
    algorithm = Column(String(32), nullable=False, default="argon2id", server_default="argon2id")
    parameters = Column(JSON, nullable=False, default=dict, server_default="{}")
    version = Column(Integer, nullable=False, default=1, server_default="1")
    status = Column(String(32), nullable=False, default="ACTIVE", server_default="ACTIVE")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    changed_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    expires_at = Column(DateTime(timezone=True), nullable=True)
    last_used_at = Column(DateTime(timezone=True), nullable=True)
    revoked_at = Column(DateTime(timezone=True), nullable=True)
    revoked_reason = Column(String(255), nullable=True)


class PasswordHistory(Base):
    """Immutable password hash history used to prevent credential reuse."""

    __tablename__ = "iam_password_history"
    __table_args__ = (
        Index("ix_iam_password_history_user_created", "tenant_id", "user_id", "created_at"),
        CheckConstraint("algorithm = 'argon2id'", name="ck_iam_password_history_algorithm"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_iam_password_history_tenant_nonempty"),
    )

    history_id = Column(String, primary_key=True)
    user_id = Column(String, nullable=False)
    tenant_id = Column(String, nullable=False)
    password_hash = Column(Text, nullable=False)
    algorithm = Column(String(32), nullable=False, default="argon2id", server_default="argon2id")
    parameters = Column(JSON, nullable=False, default=dict, server_default="{}")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class PasswordResetState(Base):
    """Hashed, single-use password reset state with explicit lifecycle."""

    __tablename__ = "iam_password_reset_states"
    __table_args__ = (
        Index("ix_iam_password_reset_user_status", "tenant_id", "user_id", "status"),
        CheckConstraint("status IN ('PENDING', 'USED', 'EXPIRED', 'REVOKED')", name="ck_iam_password_reset_status"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_iam_password_reset_tenant_nonempty"),
    )

    reset_id = Column(String, primary_key=True)
    user_id = Column(String, nullable=False)
    tenant_id = Column(String, nullable=False)
    token_hash = Column(String(128), nullable=False, unique=True)
    status = Column(String(32), nullable=False, default="PENDING", server_default="PENDING")
    requested_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    expires_at = Column(DateTime(timezone=True), nullable=False)
    consumed_at = Column(DateTime(timezone=True), nullable=True)
    attempts = Column(Integer, nullable=False, default=0, server_default="0")


class PasswordPolicy(Base):
    """Configurable password-age policy at platform, organization, or tenant scope."""

    __tablename__ = "iam_password_policies"
    __table_args__ = (
        UniqueConstraint("scope_type", "scope_id", name="uq_iam_password_policy_scope"),
        CheckConstraint("scope_type IN ('platform', 'organization', 'tenant')", name="ck_iam_password_policy_scope_type"),
        CheckConstraint("max_age_days IS NULL OR max_age_days >= 0", name="ck_iam_password_policy_max_age"),
        CheckConstraint("privileged_max_age_days IS NULL OR privileged_max_age_days >= 0", name="ck_iam_password_policy_privileged_age"),
        CheckConstraint("notify_before_days >= 0", name="ck_iam_password_policy_notify_before"),
    )

    policy_id = Column(String, primary_key=True)
    scope_type = Column(String(32), nullable=False)
    scope_id = Column(String(255), nullable=False)
    tenant_id = Column(String, nullable=True)
    enabled = Column(Boolean, nullable=False, default=True, server_default="true")
    max_age_days = Column(Integer, nullable=True)
    privileged_max_age_days = Column(Integer, nullable=True)
    notify_before_days = Column(Integer, nullable=False, default=14, server_default="14")
    force_change = Column(Boolean, nullable=False, default=True, server_default="true")
    emergency_rotation_at = Column(DateTime(timezone=True), nullable=True)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    updated_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc), nullable=False)


class PlatformIdentity(Base):
    """Platform-level identity and lifecycle boundary above tenant accounts."""

    __tablename__ = "iam_platforms"
    __table_args__ = (
        UniqueConstraint("slug", name="uq_iam_platform_slug"),
        CheckConstraint("status IN ('active', 'suspended', 'decommissioned')", name="ck_iam_platform_status"),
    )

    id = Column(String, primary_key=True)
    name = Column(String, nullable=False)
    slug = Column(String(128), nullable=False)
    status = Column(String(32), nullable=False, default="active", server_default="active")
    attributes = Column(JSON, nullable=False, default=dict, server_default="{}")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    updated_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc), nullable=False)


class TenantRecord(Base):
    """Durable tenant identity, ownership, residency, and lifecycle state."""

    __tablename__ = "iam_tenants"
    __table_args__ = (
        UniqueConstraint("platform_id", "slug", name="uq_iam_tenant_platform_slug"),
        Index("ix_iam_tenants_platform_status", "platform_id", "status"),
        CheckConstraint("status IN ('provisioning', 'active', 'suspended', 'deleting', 'deleted')", name="ck_iam_tenant_status"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_iam_tenant_identity_nonempty"),
    )

    tenant_id = Column(String, primary_key=True)
    platform_id = Column(String, nullable=False)
    name = Column(String, nullable=False)
    slug = Column(String(128), nullable=False)
    owner_user_id = Column(String, nullable=True)
    residency_region = Column(String(64), nullable=False, default="ke-central", server_default="ke-central")
    status = Column(String(32), nullable=False, default="provisioning", server_default="provisioning")
    attributes = Column(JSON, nullable=False, default=dict, server_default="{}")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    updated_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc), nullable=False)


class ServiceIdentity(Base):
    """Tenant-owned non-human identity for services and workload credentials."""

    __tablename__ = "iam_service_identities"
    __table_args__ = (
        UniqueConstraint("tenant_id", "name", name="uq_iam_service_identity_tenant_name"),
        Index("ix_iam_service_identity_scope_status", "tenant_id", "status"),
        CheckConstraint("status IN ('active', 'suspended', 'revoked', 'decommissioned')", name="ck_iam_service_identity_status"),
        CheckConstraint("identity_type IN ('service', 'workload', 'machine')", name="ck_iam_service_identity_type"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_iam_service_identity_tenant_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False)
    name = Column(String(128), nullable=False)
    identity_type = Column(String(32), nullable=False, default="service", server_default="service")
    owner_user_id = Column(String, nullable=True)
    status = Column(String(32), nullable=False, default="active", server_default="active")
    scopes = Column(JSON, nullable=False, default=list, server_default="[]")
    attributes = Column(JSON, nullable=False, default=dict, server_default="{}")
    authorization_version = Column(Integer, nullable=False, default=1, server_default="1")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    updated_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc), nullable=False)


class ApiClientIdentity(Base):
    """Tenant-owned application identity that may represent a human or service client."""

    __tablename__ = "iam_api_clients"
    __table_args__ = (
        UniqueConstraint("tenant_id", "client_identifier", name="uq_iam_api_client_identifier"),
        Index("ix_iam_api_client_tenant_status", "tenant_id", "status"),
        CheckConstraint("status IN ('active', 'suspended', 'revoked', 'expired')", name="ck_iam_api_client_status"),
        CheckConstraint("client_type IN ('confidential', 'public', 'service')", name="ck_iam_api_client_type"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_iam_api_client_tenant_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False)
    client_identifier = Column(String(128), nullable=False)
    name = Column(String(128), nullable=False)
    client_type = Column(String(32), nullable=False, default="confidential", server_default="confidential")
    service_identity_id = Column(String, nullable=True)
    owner_user_id = Column(String, nullable=True)
    status = Column(String(32), nullable=False, default="active", server_default="active")
    scopes = Column(JSON, nullable=False, default=list, server_default="[]")
    redirect_uris = Column(JSON, nullable=False, default=list, server_default="[]")
    attributes = Column(JSON, nullable=False, default=dict, server_default="{}")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    updated_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc), nullable=False)


class ExternalIdentity(Base):
    """Stable tenant-scoped link between an external IdP subject and a local user."""

    __tablename__ = "iam_external_identities"
    __table_args__ = (
        UniqueConstraint("tenant_id", "issuer", "subject", name="uq_iam_external_identity_subject"),
        Index("ix_iam_external_identity_user", "tenant_id", "user_id"),
        CheckConstraint("status IN ('active', 'disabled', 'unlinked')", name="ck_iam_external_identity_status"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_iam_external_identity_tenant_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False)
    user_id = Column(String, nullable=False)
    provider_id = Column(String, nullable=True)
    issuer = Column(String(512), nullable=False)
    subject = Column(String(512), nullable=False)
    email = Column(String, nullable=True)
    status = Column(String(32), nullable=False, default="active", server_default="active")
    claims = Column(JSON, nullable=False, default=dict, server_default="{}")
    linked_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    last_seen_at = Column(DateTime(timezone=True), nullable=True)


class IAMRole(Base):
    """Database-backed role authority for tenant and organization authorization."""

    __tablename__ = "iam_roles"
    __table_args__ = (
        UniqueConstraint("tenant_id", "name", name="uq_iam_role_tenant_name"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_iam_role_tenant_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False)
    name = Column(String(128), nullable=False)
    description = Column(Text, nullable=True)
    status = Column(String(32), nullable=False, default="active", server_default="active")
    managed = Column(Boolean, nullable=False, default=False, server_default="false")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class IAMPermission(Base):
    """Canonical action/resource permission catalog."""

    __tablename__ = "iam_permissions"
    __table_args__ = (UniqueConstraint("name", name="uq_iam_permission_name"),)

    id = Column(String, primary_key=True)
    name = Column(String(128), nullable=False)
    resource = Column(String(128), nullable=False)
    action = Column(String(64), nullable=False)
    description = Column(Text, nullable=True)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class IAMRolePermission(Base):
    """Many-to-many role permission binding."""

    __tablename__ = "iam_role_permissions"
    __table_args__ = (UniqueConstraint("role_id", "permission_id", name="uq_iam_role_permission"),)

    id = Column(String, primary_key=True)
    role_id = Column(String, nullable=False)
    permission_id = Column(String, nullable=False)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class IAMRoleBinding(Base):
    """Scoped assignment of a role to a user, service, or API client."""

    __tablename__ = "iam_role_bindings"
    __table_args__ = (
        UniqueConstraint("tenant_id", "subject_type", "subject_id", "role_id", "scope_type", "scope_id", name="uq_iam_role_binding"),
        Index("ix_iam_role_binding_subject", "tenant_id", "subject_type", "subject_id"),
        CheckConstraint("subject_type IN ('user', 'service', 'api_client')", name="ck_iam_role_binding_subject_type"),
        CheckConstraint("scope_type IN ('tenant', 'organization', 'team', 'resource')", name="ck_iam_role_binding_scope_type"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_iam_role_binding_tenant_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False)
    subject_type = Column(String(32), nullable=False)
    subject_id = Column(String, nullable=False)
    role_id = Column(String, nullable=False)
    scope_type = Column(String(32), nullable=False, default="tenant", server_default="tenant")
    scope_id = Column(String, nullable=False)
    status = Column(String(32), nullable=False, default="active", server_default="active")
    granted_by = Column(String, nullable=False)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class Organization(Base):
    """Top-level SaaS organization or customer tenant container."""

    __tablename__ = "organizations"
    __table_args__ = (
        Index("ix_organizations_tenant_slug", "tenant_id", "slug", unique=True),
        Index("ix_organizations_tenant_id", "tenant_id"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_organizations_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False, default="default")
    name = Column(String, nullable=False)
    slug = Column(String, nullable=False)
    owner_user_id = Column(String, nullable=True)
    status = Column(String, nullable=False, default="active")
    settings = Column(JSON, nullable=False, default=dict)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    updated_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc), nullable=False)


class Team(Base):
    """Functional team within an organization."""

    __tablename__ = "teams"
    __table_args__ = (
        Index("ix_teams_organization_tenant", "organization_id", "tenant_id"),
        Index("ix_teams_slug", "tenant_id", "slug", unique=False),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_teams_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False, default="default")
    organization_id = Column(String, nullable=False)
    name = Column(String, nullable=False)
    slug = Column(String, nullable=False)
    description = Column(Text, nullable=True)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class Department(Base):
    """Department or sub-unit under a team or organization."""

    __tablename__ = "departments"
    __table_args__ = (
        Index("ix_departments_organization_tenant", "organization_id", "tenant_id"),
        Index("ix_departments_team", "team_id"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_departments_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False, default="default")
    organization_id = Column(String, nullable=False)
    team_id = Column(String, nullable=True)
    name = Column(String, nullable=False)
    slug = Column(String, nullable=False)
    description = Column(Text, nullable=True)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class OrganizationMembership(Base):
    """Associates users with organizations, teams, and departments."""

    __tablename__ = "organization_memberships"
    __table_args__ = (
        Index("ix_org_membership_user_tenant", "tenant_id", "user_id"),
        Index("ix_org_membership_org", "organization_id"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_organization_memberships_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False, default="default")
    user_id = Column(String, nullable=False)
    organization_id = Column(String, nullable=False)
    team_id = Column(String, nullable=True)
    department_id = Column(String, nullable=True)
    role = Column(String, nullable=False, default="member")
    active = Column(Boolean, nullable=False, default=True)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class OrganizationApproval(Base):
    """Approval workflow records for SaaS org onboarding and lifecycle changes."""

    __tablename__ = "organization_approvals"
    __table_args__ = (
        Index("ix_org_approval_tenant", "tenant_id", "status"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_organization_approvals_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False, default="default")
    organization_id = Column(String, nullable=True)
    request_type = Column(String, nullable=False, default="create")
    requested_by = Column(String, nullable=False)
    approver_id = Column(String, nullable=True)
    status = Column(String, nullable=False, default="pending")
    reason = Column(Text, nullable=True)
    approval_metadata = Column(JSON, nullable=True, default=dict)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    reviewed_at = Column(DateTime(timezone=True), nullable=True)


class TenantConfiguration(Base):
    """Tenant-wide configuration and feature flags."""

    __tablename__ = "tenant_configurations"
    __table_args__ = (
        Index("ix_tenant_config_unique", "tenant_id", unique=True),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_tenant_configurations_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False, default="default")
    organization_id = Column(String, nullable=True)
    config = Column(JSON, nullable=False, default=dict)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    updated_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc), nullable=False)


class TenantLimit(Base):
    """Enforced limit for a tenant or organization metric."""

    __tablename__ = "tenant_limits"
    __table_args__ = (
        Index("ix_tenant_limits_tenant_metric", "tenant_id", "organization_id", "metric_name", "period", unique=True),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_tenant_limits_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False, default="default")
    organization_id = Column(String, nullable=False)
    metric_name = Column(String, nullable=False)
    limit_value = Column(Float, nullable=False, default=0.0)
    period = Column(String, nullable=False, default="monthly")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class TransactionProvenance(Base):
    """Provenance + data-quality snapshot for one transaction lifecycle.

    This is the Phase 9 exit-gate anchor: it answers where data originated,
    what changed it, which service processed it, which version processed it,
    and what the final decision was. The table is append-only once finalized.
    """

    __tablename__ = "transaction_provenances"
    __table_args__ = (
        Index("ix_prov_tenant_final", "tenant_id", "final_decision"),
        Index("ix_prov_tenant_created", "tenant_id", "created_at"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_prov_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True, default=lambda: f"prov_{uuid.uuid4().hex}")
    tenant_id = Column(String, nullable=False)
    transaction_id = Column(String, nullable=False)
    provider_id = Column(String, nullable=False)
    lineage_id = Column(String, nullable=False)
    event_id = Column(String, nullable=False)
    schema_version = Column(String(64), nullable=False, default="1.0")
    pipeline_version = Column(String(64), nullable=False, default="1.0")
    raw_payload_sha256 = Column(String(64), nullable=False)
    provider_payload_sha256 = Column(String(64), nullable=True)
    provider_source_ip = Column(String, nullable=True)
    provider_signature_verified = Column(Boolean, nullable=False, default=False)
    normalized_payload_sha256 = Column(String(64), nullable=True)
    normalized_at = Column(DateTime(timezone=True), nullable=True)
    fraud_decision = Column(String, nullable=True)
    fraud_checked_at = Column(DateTime(timezone=True), nullable=True)
    reconciliation_decision = Column(String, nullable=True)
    reconciliation_checked_at = Column(DateTime(timezone=True), nullable=True)
    final_decision = Column(String, nullable=True)
    dq_status = Column(String(16), nullable=False, default="pass")
    dq_overall_score = Column(Numeric(5, 4), nullable=False, default=1.0)
    dq_completeness = Column(Numeric(5, 4), nullable=False, default=1.0)
    dq_validity = Column(Numeric(5, 4), nullable=False, default=1.0)
    dq_accuracy = Column(Numeric(5, 4), nullable=False, default=1.0)
    dq_timeliness = Column(Numeric(5, 4), nullable=False, default=1.0)
    dq_consistency = Column(Numeric(5, 4), nullable=False, default=1.0)
    dq_uniqueness = Column(Numeric(5, 4), nullable=False, default=1.0)
    dq_failures = Column(JSON, nullable=False, default=list)
    dq_warnings = Column(JSON, nullable=False, default=list)
    dq_checks = Column(JSON, nullable=False, default=list)
    dq_checked_at = Column(DateTime(timezone=True), nullable=False)
    lineage_snapshot = Column(JSON, nullable=False, default=list)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    finalized_at = Column(DateTime(timezone=True), nullable=True)


class QuarantineRecord(Base):
    """Quarantined payload that failed one or more data-quality checks.

    Quarantine is the safe destination for bad records. Nothing from quarantine
    automatically contaminates production transaction datasets.
    """

    __tablename__ = "quarantine_records"
    __table_args__ = (
        Index("ix_quarantine_tenant_reason", "tenant_id", "reason"),
        UniqueConstraint("id", "tenant_id", name="uq_quarantine_records_id_tenant"),
        UniqueConstraint(
            "tenant_id",
            "provider_id",
            "deduplication_key",
            name="uq_quarantine_records_deduplication_key",
        ),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_quarantine_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True, default=lambda: f"q_{uuid.uuid4().hex}")
    tenant_id = Column(String, nullable=False)
    transaction_id = Column(String, nullable=True)
    provider_id = Column(String, nullable=False)
    lineage_id = Column(String, nullable=True)
    reason = Column(String, nullable=False)
    failure_rule = Column(String, nullable=True)
    payload = Column(JSON, nullable=False)
    payload_sha256 = Column(String(64), nullable=False)
    rejection_context = Column(JSON, nullable=True)
    source_ip = Column(String, nullable=True)
    deduplication_key = Column(String(64), nullable=True)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class QuarantineRemediation(Base):
    """Append-only, tenant-scoped audit entry for quarantine remediation."""

    __tablename__ = "quarantine_remediations"
    __table_args__ = (
        ForeignKeyConstraint(
            ["quarantine_id", "tenant_id"],
            ["quarantine_records.id", "quarantine_records.tenant_id"],
            ondelete="RESTRICT",
            name="fk_quarantine_remediations_quarantine",
        ),
        Index(
            "ix_quarantine_remediations_scope_history",
            "tenant_id",
            "quarantine_id",
            "created_at",
        ),
        CheckConstraint(
            "tenant_id IS NOT NULL AND tenant_id <> ''",
            name="ck_quarantine_remediations_tenant_id_nonempty",
        ),
        CheckConstraint(
            "action IN ('review_started', 'correction_recorded', "
            "'replay_requested', 'replay_completed', 'resolved', 'false_positive')",
            name="ck_quarantine_remediations_action",
        ),
        CheckConstraint(
            "to_status IN ('reviewing', 'corrected', 'replay_pending', "
            "'resolved', 'false_positive')",
            name="ck_quarantine_remediations_status",
        ),
        CheckConstraint(
            "(action = 'review_started' AND from_status = 'open' AND to_status = 'reviewing') "
            "OR (action = 'correction_recorded' AND from_status IN ('open', 'reviewing') "
            "AND to_status = 'corrected') "
            "OR (action = 'replay_requested' AND from_status = 'corrected' "
            "AND to_status = 'replay_pending') "
            "OR (action = 'replay_completed' AND from_status = 'replay_pending' "
            "AND to_status = 'resolved') "
            "OR (action = 'resolved' AND from_status IN "
            "('open', 'reviewing', 'corrected', 'replay_pending') AND to_status = 'resolved') "
            "OR (action = 'false_positive' AND from_status IN "
            "('open', 'reviewing', 'corrected') AND to_status = 'false_positive')",
            name="ck_quarantine_remediations_transition",
        ),
    )

    id = Column(String, primary_key=True, default=lambda: f"qr_{uuid.uuid4().hex}")
    tenant_id = Column(String, nullable=False)
    quarantine_id = Column(String, nullable=False)
    action = Column(String(32), nullable=False)
    from_status = Column(String(24), nullable=True)
    to_status = Column(String(24), nullable=False)
    actor_id = Column(String(128), nullable=False)
    evidence_sha256 = Column(String(64), nullable=True)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


@event.listens_for(QuarantineRemediation, "before_update")
def _prevent_quarantine_remediation_update(_mapper, _connection, _record):
    raise ValueError("quarantine remediation history is append-only")


@event.listens_for(QuarantineRemediation, "before_delete")
def _prevent_quarantine_remediation_delete(_mapper, _connection, _record):
    raise ValueError("quarantine remediation history is append-only")


class TenantUsage(Base):
    """Usage tracking for tenant and organization resource consumption."""

    __tablename__ = "tenant_usage"
    __table_args__ = (
        Index("ix_tenant_usage_tenant_metric", "tenant_id", "organization_id", "metric_name", "period", unique=True),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_tenant_usage_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False, default="default")
    organization_id = Column(String, nullable=False)
    metric_name = Column(String, nullable=False)
    current_usage = Column(Float, nullable=False, default=0.0)
    period = Column(String, nullable=False, default="monthly")
    updated_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc), nullable=False)


class UserSession(Base):
    """Authenticated session record for device and session risk evaluation."""

    __tablename__ = "user_sessions"
    __table_args__ = (
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_user_sessions_tenant_id_nonempty"),
        Index("ix_user_sessions_owner_activity", "tenant_id", "user_id", "last_activity_at"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False, default="default")
    user_id = Column(String, nullable=True)
    organization_id = Column(String, nullable=True)
    device_id = Column(String, nullable=True)
    user_agent = Column(String, nullable=True)
    ip_address = Column(String, nullable=True)
    location_info = Column(JSON, nullable=True, default=dict)
    state = Column(String(16), nullable=False, default="ACTIVE", server_default="ACTIVE")
    active = Column(Boolean, nullable=False, default=True)
    issued_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    last_activity_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    expires_at = Column(DateTime(timezone=True), nullable=True)
    absolute_expires_at = Column(DateTime(timezone=True), nullable=True)
    revoked_at = Column(DateTime(timezone=True), nullable=True)
    authentication_method = Column(String(64), nullable=True)
    mfa_verified = Column(Boolean, nullable=False, default=False, server_default="false")
    session_metadata = Column(JSON, nullable=True, default=dict)


class DeviceIdentity(Base):
    """Tenant- and user-scoped identity and revocation state for a client device."""

    __tablename__ = "user_devices"
    __table_args__ = (
        UniqueConstraint("tenant_id", "user_id", "device_id", name="uq_user_devices_owner_device"),
        Index("ix_user_devices_owner_last_seen", "tenant_id", "user_id", "last_seen_at"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_user_devices_tenant_id_nonempty"),
        CheckConstraint("session_count >= 0", name="ck_user_devices_session_count_nonnegative"),
    )

    id = Column(String, primary_key=True, default=lambda: f"dev_{uuid.uuid4().hex}")
    tenant_id = Column(String, nullable=False)
    user_id = Column(String, nullable=False)
    device_id = Column(String(255), nullable=False)
    device_name = Column(String(128), nullable=False, default="Unknown device")
    browser = Column(String(128), nullable=True)
    operating_system = Column(String(128), nullable=True)
    user_agent = Column(Text, nullable=True)
    first_seen_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    last_seen_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    last_ip_address = Column(String(64), nullable=True)
    session_count = Column(Integer, nullable=False, default=0, server_default="0")
    trusted = Column(Boolean, nullable=False, default=False, server_default="false")
    revoked_at = Column(DateTime(timezone=True), nullable=True)
    risk_metadata = Column(JSON, nullable=False, default=dict, server_default="{}")


class OIDCProvider(Base):
    """Tenant-managed external OIDC identity provider configuration."""

    __tablename__ = "oidc_providers"
    __table_args__ = (
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_oidc_providers_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False, default="default")
    provider_name = Column(String, nullable=False)
    issuer = Column(String, nullable=False)
    client_id = Column(String, nullable=True)
    client_secret = Column(String, nullable=True)
    authorization_endpoint = Column(String, nullable=True)
    token_endpoint = Column(String, nullable=True)
    userinfo_endpoint = Column(String, nullable=True)
    jwks_uri = Column(String, nullable=True)
    scopes = Column(JSON, nullable=False, default=list)
    allowed_roles = Column(JSON, nullable=False, default=list)
    auto_provision = Column(Boolean, nullable=False, default=False)
    claim_mapping = Column(JSON, nullable=True, default=dict)
    provider_metadata = Column("provider_metadata", JSON, nullable=True, default=dict)
    enabled = Column(Boolean, nullable=False, default=True)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    updated_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc), nullable=False)


class PaymentProvider(Base):
    """Tenant-scoped payment provider registration and operational configuration."""

    __tablename__ = "payment_providers"
    __table_args__ = (
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_payment_providers_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False, default="default", index=True)
    name = Column(String, nullable=False)
    provider_type = Column(String, nullable=False, default="payment")
    status = Column(String, nullable=False, default="active")
    credentials = Column(JSON, nullable=False, default=dict)
    api_configuration = Column(JSON, nullable=False, default=dict)
    account_configuration = Column(JSON, nullable=False, default=dict)
    provider_metadata = Column("metadata", JSON, nullable=False, default=dict)
    supported_currencies = Column(JSON, nullable=False, default=list)
    capabilities = Column(JSON, nullable=False, default=list)
    webhook_configuration = Column(JSON, nullable=False, default=dict)
    connection_status = Column(String, nullable=False, default="unknown")
    connection_checked_at = Column(DateTime(timezone=True), nullable=True)
    health_status = Column(String, nullable=False, default="unknown")
    health_details = Column(JSON, nullable=False, default=dict)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    updated_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc), nullable=False)


class ApiKeyRecord(Base):
    """Tenant-scoped API keys issued for machine access."""

    __tablename__ = "api_key_records"
    __table_args__ = (
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_api_key_records_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False, default="default")
    key_hash = Column(String(128), nullable=False, unique=True, index=True)
    key_prefix = Column(String(32), nullable=False)
    role = Column(String, nullable=False, default="read-only")
    scopes = Column(JSON, nullable=False, default=list, server_default="[]")
    expires_at = Column(DateTime(timezone=True), nullable=True)
    revoked_at = Column(DateTime(timezone=True), nullable=True)
    last_used_at = Column(DateTime(timezone=True), nullable=True)
    rotated_from_id = Column(String, nullable=True)
    api_metadata = Column("api_metadata", JSON, nullable=True, default=dict)
    active = Column(Boolean, nullable=False, default=True)
    source_version = Column(Integer, nullable=False, default=0, server_default="0")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class ApiKeyLifecycleIdempotencyRecord(Base):
    """Short-lived durable replay records for internal API-key operations."""

    __tablename__ = "api_key_lifecycle_idempotency_records"
    __table_args__ = (
        UniqueConstraint(
            "service_subject",
            "operation",
            "key_id",
            "idempotency_key",
            name="uq_api_key_lifecycle_idempotency",
        ),
        Index(
            "ix_api_key_lifecycle_idempotency_expiry",
            "expires_at",
        ),
        CheckConstraint(
            "operation IN ('sync', 'revoke', 'suspend')",
            name="ck_api_key_lifecycle_idempotency_operation",
        ),
    )

    id = Column(String(36), primary_key=True)
    service_subject = Column(String(128), nullable=False)
    operation = Column(String(16), nullable=False)
    key_id = Column(String(255), nullable=False)
    idempotency_key = Column(String(255), nullable=False)
    request_hash = Column(String(64), nullable=False)
    response = Column(JSON, nullable=False)
    created_at = Column(DateTime(timezone=True), nullable=False)
    expires_at = Column(DateTime(timezone=True), nullable=False)


class ServiceCredentialRecord(Base):
    """M2M or workload credential record subject to revocation and rotation."""

    __tablename__ = "service_credential_records"
    __table_args__ = (
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_service_credential_records_tenant_id_nonempty"),
        CheckConstraint("status IN ('active', 'revoked', 'expired')", name="ck_service_credential_records_status"),
    )

    id = Column(String, primary_key=True)
    tenant_id = Column(String, nullable=False, default="default")
    service_name = Column(String, nullable=False)
    credential_type = Column(String(32), nullable=False, default="workload")
    credential_hash = Column(String(128), nullable=False, unique=True, index=True)
    status = Column(String(16), nullable=False, default="active", server_default="active")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    revoked_at = Column(DateTime(timezone=True), nullable=True)
    revoked_by = Column(String, nullable=True)
    reason = Column(String, nullable=True)
    credential_metadata = Column(JSON, nullable=True, default=dict)


class MFAChallenge(Base):
    """MFA challenge state for end-user verification flows."""

    __tablename__ = "mfa_challenges"
    __table_args__ = (
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_mfa_challenges_tenant_id_nonempty"),
        Index("ix_mfa_challenges_owner_status", "tenant_id", "user_id", "status"),
    )

    id = Column(String, primary_key=True)
    user_id = Column(String, nullable=False)
    tenant_id = Column(String, nullable=False)
    code_hash = Column(String(128), nullable=False)
    challenge_type = Column(String(64), nullable=False, default="email_verification", server_default="email_verification")
    factor_id = Column(String, nullable=True)
    challenge_data = Column(JSON, nullable=False, default=dict, server_default="{}")
    status = Column(String, nullable=False, default="pending")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    expires_at = Column(DateTime(timezone=True), nullable=False)
    attempts = Column(Integer, nullable=False, default=0)


class MFAFactor(Base):
    """A separately managed, tenant-scoped authentication factor."""

    __tablename__ = "mfa_factors"
    __table_args__ = (
        Index("ix_mfa_factors_owner_state", "tenant_id", "user_id", "status"),
        UniqueConstraint("credential_id", name="uq_mfa_factors_credential_id"),
        CheckConstraint(
            "factor_type IN ('totp', 'webauthn')",
            name="ck_mfa_factors_type",
        ),
        CheckConstraint(
            "status IN ('pending', 'active', 'revoked', 'locked')",
            name="ck_mfa_factors_status",
        ),
        CheckConstraint("failed_attempts >= 0", name="ck_mfa_factors_failed_attempts"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_mfa_factors_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True, default=lambda: f"mfa_factor_{uuid.uuid4().hex}")
    tenant_id = Column(String, nullable=False)
    user_id = Column(String, nullable=False)
    factor_type = Column(String(32), nullable=False)
    factor_kind = Column(String(32), nullable=False, default="totp")
    display_name = Column(String(128), nullable=False, default="Authenticator")
    secret_encrypted = Column(Text, nullable=True)
    credential_id = Column(String(1024), nullable=True)
    credential_public_key = Column(Text, nullable=True)
    sign_count = Column(Integer, nullable=False, default=0, server_default="0")
    last_used_counter = Column(Integer, nullable=True)
    status = Column(String(16), nullable=False, default="pending", server_default="pending")
    failed_attempts = Column(Integer, nullable=False, default=0, server_default="0")
    locked_until = Column(DateTime(timezone=True), nullable=True)
    metadata_json = Column(JSON, nullable=False, default=dict, server_default="{}")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    confirmed_at = Column(DateTime(timezone=True), nullable=True)
    last_used_at = Column(DateTime(timezone=True), nullable=True)
    revoked_at = Column(DateTime(timezone=True), nullable=True)
    expires_at = Column(DateTime(timezone=True), nullable=True)


class MFARecovery(Base):
    """One-time hashed recovery code; plaintext is never persisted."""

    __tablename__ = "mfa_recovery_codes"
    __table_args__ = (
        Index("ix_mfa_recovery_owner_unused", "tenant_id", "user_id", "used_at"),
        UniqueConstraint("code_hash", name="uq_mfa_recovery_code_hash"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_mfa_recovery_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True, default=lambda: f"mfa_recovery_{uuid.uuid4().hex}")
    tenant_id = Column(String, nullable=False)
    user_id = Column(String, nullable=False)
    code_hash = Column(String(64), nullable=False)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    used_at = Column(DateTime(timezone=True), nullable=True)


class MFAPolicy(Base):
    """Versioned tenant policy for factors, enforcement, and challenge controls."""

    __tablename__ = "mfa_policies"
    __table_args__ = (
        UniqueConstraint("tenant_id", name="uq_mfa_policies_tenant"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_mfa_policies_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True, default=lambda: f"mfa_policy_{uuid.uuid4().hex}")
    tenant_id = Column(String, nullable=False)
    policy = Column(JSON, nullable=False, default=dict)
    version = Column(Integer, nullable=False, default=1, server_default="1")
    updated_by = Column(String, nullable=True)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    updated_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc), nullable=False)


class MFAEvent(Base):
    """Append-only security audit record for MFA factor and challenge activity."""

    __tablename__ = "mfa_events"
    __table_args__ = (
        Index("ix_mfa_events_owner_time", "tenant_id", "user_id", "created_at"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_mfa_events_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True, default=lambda: f"mfa_event_{uuid.uuid4().hex}")
    tenant_id = Column(String, nullable=False)
    user_id = Column(String, nullable=False)
    factor_id = Column(String, nullable=True)
    challenge_id = Column(String, nullable=True)
    event_type = Column(String(96), nullable=False)
    outcome = Column(String(32), nullable=False)
    details = Column(JSON, nullable=False, default=dict, server_default="{}")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class PasswordlessChallenge(Base):
    """Passwordless challenge state for email or magic-link verification."""

    __tablename__ = "passwordless_challenges"
    __table_args__ = (
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_passwordless_challenges_tenant_id_nonempty"),
    )

    id = Column(String, primary_key=True)
    user_id = Column(String, nullable=False)
    tenant_id = Column(String, nullable=False)
    token_hash = Column(String(128), nullable=False)
    status = Column(String, nullable=False, default="pending")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    expires_at = Column(DateTime(timezone=True), nullable=False)
    attempts = Column(Integer, nullable=False, default=0)


class Project(Base):
    """Tenant + organization scoped project.

    Mirrors the canonical Project domain: an immutable ``created_by`` audit
    field, an explicit lifecycle (``active`` -> ``deactivated`` <-> ``active``,
    ``active``/``deactivated`` -> ``archived``) with optimistic locking, and a
    mutable owner that can be transferred. An archived project is terminal.
    """

    __tablename__ = "projects"
    __table_args__ = (
        UniqueConstraint("tenant_id", "organization_id", "slug", name="uq_projects_org_slug"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_projects_tenant_id_nonempty"),
        CheckConstraint("status IN ('active', 'deactivated', 'archived')", name="ck_projects_status"),
        CheckConstraint("version >= 1", name="ck_projects_version_positive"),
        Index("ix_projects_owner_state", "tenant_id", "organization_id", "status"),
    )

    id = Column(String(36), primary_key=True, default=lambda: f"project_{uuid.uuid4().hex[:12]}")
    tenant_id = Column(String, nullable=False)
    organization_id = Column(String, nullable=False, index=True)
    name = Column(String(120), nullable=False)
    slug = Column(String(80), nullable=False)
    status = Column(String(16), nullable=False, default="active", server_default="active")
    created_by = Column(String, nullable=False)
    owner_user_id = Column(String, nullable=False)
    description = Column(String(500), nullable=True)
    version = Column(Integer, nullable=False, default=1, server_default="1")
    # DB column stays "metadata" (parallel to the canonical domain); the Python
    # attribute avoids SQLAlchemy's reserved ``metadata`` on the declarative base.
    project_metadata = Column("metadata", JSON, nullable=False, default=dict, server_default="{}")
    status_changed_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    updated_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc), nullable=False)


class ProjectEnvironment(Base):
    """An environment (tier) belonging to a project, tenant + org scoped.

    Tier isolation is a hard rule: an environment has exactly one ordered tier
    (``development`` < ``sandbox`` < ``staging`` < ``production``) and may only
    be promoted one tier at a time. A ``deactivated`` environment is terminal.
    """

    __tablename__ = "project_environments"
    __table_args__ = (
        UniqueConstraint("tenant_id", "project_id", "name", name="uq_project_env_name"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_project_env_tenant_id_nonempty"),
        CheckConstraint("type IN ('development', 'sandbox', 'staging', 'production')", name="ck_project_env_type"),
        CheckConstraint("status IN ('active', 'suspended', 'deactivated')", name="ck_project_env_status"),
        CheckConstraint("version >= 1", name="ck_project_env_version_positive"),
        Index("ix_project_env_owner_state", "tenant_id", "project_id", "status"),
    )

    id = Column(String(36), primary_key=True, default=lambda: f"env_{uuid.uuid4().hex[:12]}")
    tenant_id = Column(String, nullable=False)
    organization_id = Column(String, nullable=False, index=True)
    project_id = Column(String, nullable=False, index=True)
    name = Column(String(80), nullable=False)
    environment_type = Column("type", String(16), nullable=False)
    status = Column(String(16), nullable=False, default="active", server_default="active")
    created_by = Column(String, nullable=False)
    configuration = Column(JSON, nullable=False, default=dict, server_default="{}")
    version = Column(Integer, nullable=False, default=1, server_default="1")
    status_changed_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    updated_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc), nullable=False)


class Settlement(Base):
    """Tenant-scoped internal calculation over caller-supplied settlement lines.

    Financial safety: all monetary columns use NUMERIC (Decimal), never Float.
    Every row is tenant-isolated and carries a non-empty tenant_id constraint so
    cross-tenant leakage is impossible at the database layer. The unique
    (tenant_id, settlement_key, currency) constraint makes settlement creation
    idempotent per tenant period and currency. Internal statuses do not verify
    provider statements or attest that funds were transferred.
    """

    __tablename__ = "settlements"
    __table_args__ = (
        UniqueConstraint("tenant_id", "settlement_key", "currency", name="uq_settlements_tenant_key_currency"),
        UniqueConstraint("id", "tenant_id", name="uq_settlements_id_tenant"),
        Index("ix_settlements_tenant_period", "tenant_id", "period_start", "period_end"),
        Index("ix_settlements_tenant_status", "tenant_id", "status"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_settlements_tenant_id_nonempty"),
        CheckConstraint("gross_amount >= 0", name="ck_settlements_gross_nonnegative"),
        CheckConstraint("fee_amount >= 0", name="ck_settlements_fee_nonnegative"),
        CheckConstraint("net_amount >= 0", name="ck_settlements_net_nonnegative"),
        CheckConstraint("transaction_count >= 0", name="ck_settlements_count_nonnegative"),
        CheckConstraint("version >= 1", name="ck_settlements_version_positive"),
        CheckConstraint("length(currency) = 3 AND currency = upper(currency)", name="ck_settlements_currency_iso"),
        CheckConstraint(
            "status IN ('pending','calculating','calculated','reconciling',"
            "'reconciled','settled','failed','disputed')",
            name="ck_settlements_status_valid",
        ),
        CheckConstraint("period_end >= period_start", name="ck_settlements_period_ordered"),
    )

    id = Column(String, primary_key=True, default=lambda: f"stl_{uuid.uuid4().hex}")
    tenant_id = Column(String, nullable=False)
    settlement_key = Column(String(255), nullable=False)
    currency = Column(String(3), nullable=False, default="KES", server_default="KES")
    period_start = Column(DateTime(timezone=True), nullable=False)
    period_end = Column(DateTime(timezone=True), nullable=False)
    status = Column(String(32), nullable=False, default="pending", server_default="pending")
    transaction_count = Column(Integer, nullable=False, default=0, server_default="0")
    gross_amount = Column(Numeric(19, 3), nullable=False, default=0, server_default="0")
    fee_amount = Column(Numeric(19, 3), nullable=False, default=0, server_default="0")
    net_amount = Column(Numeric(19, 3), nullable=False, default=0, server_default="0")
    # Reconciliation targets supplied by the provider statement (nullable until
    # a statement is imported). variance_amount is derived: expected - net.
    expected_net_amount = Column(Numeric(19, 3), nullable=True)
    variance_amount = Column(Numeric(19, 3), nullable=True)
    computed_at = Column(DateTime(timezone=True), nullable=True)
    reconciled_at = Column(DateTime(timezone=True), nullable=True)
    settled_at = Column(DateTime(timezone=True), nullable=True)
    version = Column(Integer, nullable=False, default=1, server_default="1")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    updated_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    notes = Column(Text, nullable=True)

    @property
    def is_terminal(self) -> bool:
        return self.status in {"settled", "failed", "disputed"}

    __mapper_args__ = {"version_id_col": version}


class SettlementAuditEvent(Base):
    """Append-only record of internal settlement calculations and decisions."""

    __tablename__ = "settlement_audit_events"
    __table_args__ = (
        CheckConstraint(
            "tenant_id IS NOT NULL AND tenant_id <> ''",
            name="ck_settlement_audit_events_tenant_id_nonempty",
        ),
        CheckConstraint(
            "event_type IN ('calculated', 'reconciled', 'status_transition', "
            "'transition_rejected')",
            name="ck_settlement_audit_events_type",
        ),
        ForeignKeyConstraint(
            ["settlement_id", "tenant_id"],
            ["settlements.id", "settlements.tenant_id"],
            name="fk_settlement_audit_events_settlement_tenant",
            ondelete="RESTRICT",
        ),
        Index(
            "ix_settlement_audit_events_scope_history",
            "tenant_id",
            "settlement_id",
            "created_at",
        ),
    )

    id = Column(String, primary_key=True, default=lambda: f"sta_{uuid.uuid4().hex}")
    tenant_id = Column(String, nullable=False)
    settlement_id = Column(String, nullable=False)
    event_type = Column(String(32), nullable=False)
    from_status = Column(String(32), nullable=True)
    to_status = Column(String(32), nullable=True)
    details = Column(JSON, nullable=False, default=dict)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class SettlementLine(Base):
    """One transaction contributing to a settlement (auditable lineage)."""

    __tablename__ = "settlement_lines"
    __table_args__ = (
        UniqueConstraint("settlement_id", "transaction_id", name="uq_settlement_lines_settlement_tx"),
        Index("ix_settlement_lines_tenant_settlement", "tenant_id", "settlement_id"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_settlement_lines_tenant_id_nonempty"),
        CheckConstraint("amount >= 0", name="ck_settlement_lines_amount_nonnegative"),
        CheckConstraint("fee >= 0", name="ck_settlement_lines_fee_nonnegative"),
        CheckConstraint("net >= 0", name="ck_settlement_lines_net_nonnegative"),
    )

    id = Column(String, primary_key=True, default=lambda: f"stli_{uuid.uuid4().hex}")
    tenant_id = Column(String, nullable=False)
    settlement_id = Column(String, nullable=False)
    transaction_id = Column(String, nullable=False)
    trans_id = Column(String, nullable=True)
    provider = Column(String, nullable=True)
    amount = Column(Numeric(19, 3), nullable=False, default=0, server_default="0")
    fee = Column(Numeric(19, 3), nullable=False, default=0, server_default="0")
    net = Column(Numeric(19, 3), nullable=False, default=0, server_default="0")
    status = Column(String(32), nullable=False, default="included", server_default="included")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class ScheduledJob(Base):
    """Durable, tenant-scoped scheduled job definition (survives restarts).

    The scheduler computes due runs deterministically from an injected clock;
    single execution across workers is enforced with a distributed lock, and the
    lock_owner/locked_until columns make accidental double-execution observable.
    """

    __tablename__ = "scheduled_jobs"
    __table_args__ = (
        UniqueConstraint("tenant_id", "job_key", name="uq_scheduled_jobs_tenant_key"),
        UniqueConstraint("id", "tenant_id", name="uq_scheduled_jobs_id_tenant"),
        Index("ix_scheduled_jobs_due", "enabled", "next_run_at"),
        Index("ix_scheduled_jobs_tenant_task", "tenant_id", "task_name"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_scheduled_jobs_tenant_id_nonempty"),
        CheckConstraint("interval_seconds > 0", name="ck_scheduled_jobs_interval_positive"),
        CheckConstraint("run_count >= 0", name="ck_scheduled_jobs_run_count_nonnegative"),
        CheckConstraint("failure_count >= 0", name="ck_scheduled_jobs_failure_count_nonnegative"),
        CheckConstraint("max_attempts BETWEEN 1 AND 5", name="ck_scheduled_jobs_max_attempts"),
        CheckConstraint(
            "retry_backoff_seconds BETWEEN 1 AND 3600",
            name="ck_scheduled_jobs_retry_backoff",
        ),
        CheckConstraint(
            "timeout_seconds IS NULL OR timeout_seconds BETWEEN 1 AND 3600",
            name="ck_scheduled_jobs_timeout",
        ),
    )

    id = Column(String, primary_key=True, default=lambda: f"sched_{uuid.uuid4().hex}")
    tenant_id = Column(String, nullable=False)
    job_key = Column(String(255), nullable=False)
    task_name = Column(String(255), nullable=False)
    interval_seconds = Column(Integer, nullable=False)
    enabled = Column(Boolean, nullable=False, default=True, server_default="true")
    next_run_at = Column(DateTime(timezone=True), nullable=True)
    retry_at = Column(DateTime(timezone=True), nullable=True)
    last_run_at = Column(DateTime(timezone=True), nullable=True)
    locked_until = Column(DateTime(timezone=True), nullable=True)
    lock_owner = Column(String(255), nullable=True)
    run_count = Column(Integer, nullable=False, default=0, server_default="0")
    failure_count = Column(Integer, nullable=False, default=0, server_default="0")
    max_attempts = Column(Integer, nullable=False, default=3, server_default="3")
    retry_backoff_seconds = Column(Integer, nullable=False, default=30, server_default="30")
    timeout_seconds = Column(Integer, nullable=True)
    last_error = Column(Text, nullable=True)
    payload = Column(JSON, nullable=False, default=dict, server_default="{}")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    updated_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class ScheduledJobRun(Base):
    """Tenant-scoped durable record for each attempt of a scheduled occurrence."""

    __tablename__ = "scheduled_job_runs"
    __table_args__ = (
        UniqueConstraint(
            "job_id",
            "tenant_id",
            "scheduled_for",
            "attempt_number",
            name="uq_scheduled_job_runs_occurrence_attempt",
        ),
        ForeignKeyConstraint(
            ["job_id", "tenant_id"],
            ["scheduled_jobs.id", "scheduled_jobs.tenant_id"],
            name="fk_scheduled_job_runs_job_tenant",
        ),
        ForeignKeyConstraint(
            ["backfill_id", "tenant_id"],
            ["scheduled_job_backfills.id", "scheduled_job_backfills.tenant_id"],
            name="fk_scheduled_job_runs_backfill_tenant",
        ),
        Index("ix_scheduled_job_runs_tenant_job_started", "tenant_id", "job_key", "started_at"),
        Index("ix_scheduled_job_runs_status_started", "status", "started_at"),
        CheckConstraint(
            "tenant_id IS NOT NULL AND tenant_id <> ''",
            name="ck_scheduled_job_runs_tenant_id_nonempty",
        ),
        CheckConstraint(
            "attempt_number BETWEEN 1 AND 5",
            name="ck_scheduled_job_runs_attempt_number",
        ),
        CheckConstraint(
            "status IN ('running', 'interrupted', 'failed', 'succeeded')",
            name="ck_scheduled_job_runs_status",
        ),
        CheckConstraint(
            "trigger_type IN ('scheduled', 'backfill')",
            name="ck_scheduled_job_runs_trigger_type",
        ),
        CheckConstraint(
            "(trigger_type = 'scheduled' AND backfill_id IS NULL) OR "
            "(trigger_type = 'backfill' AND backfill_id IS NOT NULL)",
            name="ck_scheduled_job_runs_backfill_reference",
        ),
    )

    id = Column(String, primary_key=True, default=lambda: f"jobrun_{uuid.uuid4().hex}")
    job_id = Column(String, nullable=False)
    tenant_id = Column(String, nullable=False)
    job_key = Column(String(255), nullable=False)
    scheduled_for = Column(DateTime(timezone=True), nullable=False)
    attempt_number = Column(Integer, nullable=False)
    status = Column(String(16), nullable=False)
    trigger_type = Column(String(16), nullable=False, default="scheduled", server_default="scheduled")
    backfill_id = Column(String, nullable=True)
    worker_id = Column(String(255), nullable=False)
    started_at = Column(DateTime(timezone=True), nullable=False)
    finished_at = Column(DateTime(timezone=True), nullable=True)
    error_type = Column(String(255), nullable=True)
    resolution_by = Column(String(255), nullable=True)
    resolution_reason = Column(Text, nullable=True)


class ScheduledJobDependency(Base):
    """Tenant-local dependency edge between two scheduled jobs."""

    __tablename__ = "scheduled_job_dependencies"
    __table_args__ = (
        ForeignKeyConstraint(
            ["job_id", "tenant_id"],
            ["scheduled_jobs.id", "scheduled_jobs.tenant_id"],
            name="fk_scheduled_job_dependencies_job_tenant",
        ),
        ForeignKeyConstraint(
            ["depends_on_job_id", "tenant_id"],
            ["scheduled_jobs.id", "scheduled_jobs.tenant_id"],
            name="fk_scheduled_job_dependencies_parent_tenant",
        ),
        CheckConstraint("job_id <> depends_on_job_id", name="ck_scheduled_job_dependencies_not_self"),
        Index("ix_scheduled_job_dependencies_parent", "tenant_id", "depends_on_job_id"),
    )

    tenant_id = Column(String, primary_key=True)
    job_id = Column(String, primary_key=True)
    depends_on_job_id = Column(String, primary_key=True)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class ScheduledJobBackfill(Base):
    """Auditable, idempotent historical execution request for one scheduled job."""

    __tablename__ = "scheduled_job_backfills"
    __table_args__ = (
        UniqueConstraint(
            "tenant_id",
            "job_id",
            "idempotency_key",
            name="uq_scheduled_job_backfills_idempotency",
        ),
        UniqueConstraint("id", "tenant_id", name="uq_scheduled_job_backfills_id_tenant"),
        ForeignKeyConstraint(
            ["job_id", "tenant_id"],
            ["scheduled_jobs.id", "scheduled_jobs.tenant_id"],
            name="fk_scheduled_job_backfills_job_tenant",
        ),
        CheckConstraint(
            "status IN ('queued', 'running', 'retrying', 'succeeded', 'failed', 'interrupted')",
            name="ck_scheduled_job_backfills_status",
        ),
        CheckConstraint("attempt_count BETWEEN 0 AND 5", name="ck_scheduled_job_backfills_attempt_count"),
        CheckConstraint("end_at > start_at", name="ck_scheduled_job_backfills_window"),
        Index("ix_scheduled_job_backfills_due", "tenant_id", "status", "retry_at"),
        Index("ix_scheduled_job_backfills_job_created", "tenant_id", "job_id", "created_at"),
    )

    id = Column(String, primary_key=True, default=lambda: f"backfill_{uuid.uuid4().hex}")
    tenant_id = Column(String, nullable=False)
    job_id = Column(String, nullable=False)
    job_key = Column(String(255), nullable=False)
    start_at = Column(DateTime(timezone=True), nullable=False)
    end_at = Column(DateTime(timezone=True), nullable=False)
    idempotency_key = Column(String(255), nullable=False)
    status = Column(String(16), nullable=False, default="queued", server_default="queued")
    attempt_count = Column(Integer, nullable=False, default=0, server_default="0")
    max_attempts = Column(Integer, nullable=False, default=3, server_default="3")
    retry_at = Column(DateTime(timezone=True), nullable=True)
    requested_by = Column(String(255), nullable=False)
    reason = Column(Text, nullable=False)
    error_type = Column(String(255), nullable=True)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    updated_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)


class Ledger(Base):
    """A double-entry ledger: the top-level books for one tenant product scope."""

    __tablename__ = "ledgers"
    __table_args__ = (
        UniqueConstraint("tenant_id", "slug", name="uq_ledgers_tenant_slug"),
        UniqueConstraint("tenant_id", "id", name="uq_ledgers_tenant_id"),
        Index("ix_ledgers_tenant_status", "tenant_id", "status"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_ledgers_tenant_id_nonempty"),
        CheckConstraint("status IN ('active', 'archived')", name="ck_ledgers_status"),
    )

    id = Column(String, primary_key=True, default=lambda: f"ledger_{uuid.uuid4().hex[:12]}")
    tenant_id = Column(String, nullable=False)
    slug = Column(String(80), nullable=False)
    name = Column(String(120), nullable=False)
    description = Column(Text, nullable=True)
    status = Column(String(16), nullable=False, default="active", server_default="active")
    version = Column(Integer, nullable=False, default=1, server_default="1")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    updated_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc), nullable=False)

    __mapper_args__ = {"version_id_col": version}


class LedgerAccount(Base):
    """A balance holder inside one Ledger (user wallet, operating, fees)."""

    __tablename__ = "ledger_accounts"
    __table_args__ = (
        UniqueConstraint("tenant_id", "ledger_id", "code", name="uq_ledger_accounts_ledger_code"),
        UniqueConstraint("tenant_id", "id", name="uq_ledger_accounts_tenant_id"),
        UniqueConstraint("tenant_id", "ledger_id", "id", name="uq_ledger_accounts_ledger_id"),
        ForeignKeyConstraint(
            ["ledger_id", "tenant_id"],
            ["ledgers.id", "ledgers.tenant_id"],
            name="fk_ledger_accounts_ledger_tenant",
            ondelete="RESTRICT",
        ),
        Index("ix_ledger_accounts_scope", "tenant_id", "ledger_id", "status"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_ledger_accounts_tenant_id_nonempty"),
        CheckConstraint("status IN ('active', 'suspended', 'closed')", name="ck_ledger_accounts_status"),
        CheckConstraint("normal_balance IN ('debit', 'credit')", name="ck_ledger_accounts_normal_balance"),
        CheckConstraint("length(currency) = 3 AND currency = upper(currency)", name="ck_ledger_accounts_currency_iso"),
        CheckConstraint("lock_version >= 1", name="ck_ledger_accounts_lock_version_positive"),
    )

    id = Column(String, primary_key=True, default=lambda: f"lacc_{uuid.uuid4().hex[:12]}")
    tenant_id = Column(String, nullable=False)
    ledger_id = Column(String, nullable=False)
    code = Column(String(80), nullable=False)
    name = Column(String(120), nullable=False)
    currency = Column(String(3), nullable=False, default="KES", server_default="KES")
    normal_balance = Column(String(8), nullable=False, default="debit", server_default="debit")
    status = Column(String(16), nullable=False, default="active", server_default="active")
    lock_version = Column(Integer, nullable=False, default=1, server_default="1")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
    updated_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc), nullable=False)

    __mapper_args__ = {"version_id_col": lock_version}


class LedgerTransaction(Base):
    """One atomic financial event: >=2 entries balancing per currency."""

    __tablename__ = "ledger_transactions"
    __table_args__ = (
        UniqueConstraint("tenant_id", "ledger_id", "external_id", name="uq_ledger_tx_ledger_external"),
        UniqueConstraint("tenant_id", "id", name="uq_ledger_tx_tenant_id"),
        UniqueConstraint("tenant_id", "ledger_id", "id", name="uq_ledger_tx_ledger_id"),
        ForeignKeyConstraint(
            ["ledger_id", "tenant_id"],
            ["ledgers.id", "ledgers.tenant_id"],
            name="fk_ledger_tx_ledger_tenant",
            ondelete="RESTRICT",
        ),
        Index("ix_ledger_tx_scope_status", "tenant_id", "ledger_id", "status", "effective_at"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_ledger_tx_tenant_id_nonempty"),
        CheckConstraint("status IN ('pending', 'posted', 'voided')", name="ck_ledger_tx_status"),
        CheckConstraint("version >= 1", name="ck_ledger_tx_version_positive"),
    )

    id = Column(String, primary_key=True, default=lambda: f"ltxn_{uuid.uuid4().hex[:12]}")
    tenant_id = Column(String, nullable=False)
    ledger_id = Column(String, nullable=False)
    external_id = Column(String(180), nullable=False)
    description = Column(Text, nullable=True)
    status = Column(String(16), nullable=False, default="pending", server_default="pending")
    effective_at = Column(DateTime(timezone=True), nullable=False, default=lambda: datetime.now(timezone.utc))
    posted_at = Column(DateTime(timezone=True), nullable=True)
    version = Column(Integer, nullable=False, default=1, server_default="1")
    ledger_metadata = Column("metadata", JSON, nullable=False, default=dict, server_default="{}")
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)

    __mapper_args__ = {"version_id_col": version}


class LedgerEntry(Base):
    """One debit or credit line of a LedgerTransaction against one account."""

    __tablename__ = "ledger_entries"
    __table_args__ = (
        UniqueConstraint("tenant_id", "id", name="uq_ledger_entries_tenant_id"),
        ForeignKeyConstraint(
            ["tenant_id", "ledger_id", "ledger_transaction_id"],
            ["ledger_transactions.tenant_id", "ledger_transactions.ledger_id", "ledger_transactions.id"],
            name="fk_ledger_entries_tx_ledger_tenant",
            ondelete="RESTRICT",
        ),
        ForeignKeyConstraint(
            ["tenant_id", "ledger_id", "ledger_account_id"],
            ["ledger_accounts.tenant_id", "ledger_accounts.ledger_id", "ledger_accounts.id"],
            name="fk_ledger_entries_account_ledger_tenant",
            ondelete="RESTRICT",
        ),
        Index("ix_ledger_entries_tx", "tenant_id", "ledger_transaction_id"),
        Index("ix_ledger_entries_account", "tenant_id", "ledger_account_id"),
        CheckConstraint("tenant_id IS NOT NULL AND tenant_id <> ''", name="ck_ledger_entries_tenant_id_nonempty"),
        CheckConstraint("direction IN ('debit', 'credit')", name="ck_ledger_entries_direction"),
        CheckConstraint("amount_minor > 0", name="ck_ledger_entries_amount_positive"),
        CheckConstraint("length(currency) = 3 AND currency = upper(currency)", name="ck_ledger_entries_currency_iso"),
    )

    id = Column(String, primary_key=True, default=lambda: f"lent_{uuid.uuid4().hex[:12]}")
    tenant_id = Column(String, nullable=False)
    ledger_id = Column(String, nullable=False)
    ledger_transaction_id = Column(String, nullable=False)
    ledger_account_id = Column(String, nullable=False)
    direction = Column(String(8), nullable=False)
    amount_minor = Column(Integer, nullable=False)
    currency = Column(String(3), nullable=False, default="KES", server_default="KES")
    memo = Column(Text, nullable=True)
    created_at = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), nullable=False)
