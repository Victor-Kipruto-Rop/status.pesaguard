import customer_ledger


def test_dispatcher_passes_normalized_pair_currency_to_ledger(monkeypatch):
    captured = {}
    monkeypatch.setattr(customer_ledger, "_resolve_account", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(
        customer_ledger,
        "_upsert_from_pair",
        lambda _session, **kwargs: captured.update(kwargs),
    )

    dispatcher = customer_ledger.CustomerLedgerDispatcher(session=object())
    dispatcher.upsert(
        tenant_id="tenant-a",
        role="payer",
        status="MATCHED",
        account_id="account-1",
        internal_ref="transaction-1",
        phone_number="+254700000000",
        timestamp="2026-10-17T10:00:00Z",
        currency="usd",
        provider_account_id="account-1",
    )

    assert captured["currency"] == "USD"
