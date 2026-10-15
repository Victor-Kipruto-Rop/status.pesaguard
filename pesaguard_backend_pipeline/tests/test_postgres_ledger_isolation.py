"""Opt-in PostgreSQL tests for the double-entry ledger storage boundary."""

from __future__ import annotations

import os
import uuid

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.exc import DBAPIError
from sqlalchemy.orm import sessionmaker


POSTGRES_TEST_URL = os.getenv("PESAGUARD_POSTGRES_TEST_URL", "")
pytestmark = pytest.mark.skipif(
    os.getenv("PESAGUARD_RUN_POSTGRES_INTEGRATION") != "1"
    or not POSTGRES_TEST_URL.startswith(("postgresql://", "postgres://")),
    reason=(
        "set PESAGUARD_RUN_POSTGRES_INTEGRATION=1 and "
        "PESAGUARD_POSTGRES_TEST_URL to a migrated disposable PostgreSQL database"
    ),
)


def test_ledger_tables_enforce_forced_rls_and_runtime_grants():
    engine = create_engine(
        POSTGRES_TEST_URL,
        pool_size=1,
        max_overflow=0,
        pool_pre_ping=True,
    )
    try:
        with engine.connect() as connection:
            role = connection.execute(
                text(
                    "SELECT r.rolsuper, r.rolbypassrls "
                    "FROM pg_roles AS r WHERE r.rolname = current_user"
                )
            ).one()
            assert role == (False, False), (
                "the integration URL must use a non-superuser, non-BYPASSRLS role"
            )
            result = connection.execute(
                text(
                    """
                    SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity,
                           has_table_privilege(current_user, c.oid, 'SELECT'),
                           has_table_privilege(current_user, c.oid, 'INSERT'),
                           has_table_privilege(current_user, c.oid, 'UPDATE')
                    FROM pg_class AS c
                    WHERE c.oid = ANY(ARRAY[
                        'public.ledgers'::regclass,
                        'public.ledger_accounts'::regclass,
                        'public.ledger_transactions'::regclass,
                        'public.ledger_entries'::regclass
                    ])
                    ORDER BY c.relname
                    """
                )
            ).all()
            assert result == [
                ("ledger_accounts", True, True, True, True, True),
                ("ledger_entries", True, True, True, True, True),
                ("ledger_transactions", True, True, True, True, True),
                ("ledgers", True, True, True, True, True),
            ]
    finally:
        engine.dispose()


def test_ledger_reads_and_writes_are_isolated_between_tenants():
    from models import Ledger
    from tenant_context import tenant_scope

    engine = create_engine(
        POSTGRES_TEST_URL,
        pool_size=1,
        max_overflow=0,
        pool_pre_ping=True,
    )
    sessions = sessionmaker(bind=engine, expire_on_commit=False)
    suffix = uuid.uuid4().hex
    tenant_a, tenant_b = f"ledger-a-{suffix}", f"ledger-b-{suffix}"
    ledger_id = f"ledger_{suffix[:12]}"

    try:
        with tenant_scope(tenant_a), sessions() as session:
            session.add(
                Ledger(
                    id=ledger_id,
                    tenant_id=tenant_a,
                    slug=f"test-{suffix[:12]}",
                    name="RLS integration ledger",
                )
            )
            session.commit()

        with tenant_scope(tenant_b), sessions() as session:
            assert session.get(Ledger, ledger_id) is None
            session.rollback()

        with tenant_scope(tenant_a), sessions() as session:
            with pytest.raises(DBAPIError):
                session.add(
                    Ledger(
                        id=f"cross_{suffix[:12]}",
                        tenant_id=tenant_b,
                        slug=f"cross-{suffix[:12]}",
                        name="Cross-tenant ledger write",
                    )
                )
                session.commit()
            session.rollback()

        with sessions() as session:
            assert session.get(Ledger, ledger_id) is None
            session.rollback()
    finally:
        engine.dispose()


def test_posted_ledger_entries_cannot_reference_accounts_in_another_ledger():
    from double_entry_ledger import LedgerEntryInput, account_balance, post_transaction
    from models import Ledger, LedgerAccount, LedgerEntry
    from tenant_context import tenant_scope

    engine = create_engine(POSTGRES_TEST_URL)
    sessions = sessionmaker(bind=engine, expire_on_commit=False)
    tenant_id = f"ledger-integrity-{uuid.uuid4().hex}"
    ledger_a_id, ledger_b_id = f"ledger_a_{uuid.uuid4().hex[:12]}", f"ledger_b_{uuid.uuid4().hex[:12]}"

    try:
        with tenant_scope(tenant_id), sessions() as session:
            ledger_a = Ledger(
                id=ledger_a_id,
                tenant_id=tenant_id,
                slug="ledger-a",
                name="Ledger A",
            )
            ledger_b = Ledger(
                id=ledger_b_id,
                tenant_id=tenant_id,
                slug="ledger-b",
                name="Ledger B",
            )
            account_a = LedgerAccount(
                tenant_id=tenant_id,
                ledger_id=ledger_a_id,
                code="cash",
                name="Cash",
            )
            account_b = LedgerAccount(
                tenant_id=tenant_id,
                ledger_id=ledger_b_id,
                code="cash",
                name="Other ledger cash",
            )
            session.add_all([ledger_a, ledger_b, account_a, account_b])
            session.commit()

            transaction = post_transaction(
                session,
                tenant_id=tenant_id,
                ledger_id=ledger_a_id,
                external_id=f"test-{uuid.uuid4().hex}",
                entries=[
                    LedgerEntryInput("cash", "debit", 1250),
                    LedgerEntryInput("cash", "credit", 1250),
                ],
            )
            session.commit()
            assert account_balance(
                session,
                tenant_id=tenant_id,
                ledger_id=ledger_a_id,
                account_code="cash",
            )["balances_minor"] == {"KES": 0}

            session.add(
                LedgerEntry(
                    tenant_id=tenant_id,
                    ledger_id=ledger_a_id,
                    ledger_transaction_id=transaction.id,
                    ledger_account_id=account_b.id,
                    direction="debit",
                    amount_minor=1,
                    currency="KES",
                )
            )
            with pytest.raises(DBAPIError):
                session.commit()
            session.rollback()
    finally:
        engine.dispose()
