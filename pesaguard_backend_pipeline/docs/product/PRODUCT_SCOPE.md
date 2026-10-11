# PesaGuard Product Scope Decision

This document satisfies requirements **SCOPE-1** through **SCOPE-4** from Addendum III.

---

## Current scope (SCOPE-1)

**PesaGuard reconciles M-Pesa transactions via the Safaricom Daraja API only.**

| In scope | Out of scope (pilot) |
|---|---|
| Supported M-Pesa C2B/STK callback ingestion | Airtel Money |
| Internal-ledger matching (Postgres, Sheets, REST) against M-Pesa records | Card payments, PayPal, other rails |
| Anomaly detection on M-Pesa reconciliation gaps | Multi-rail unified payment inbox |
| | Daraja transaction-status query workflow |
| | Settlement execution, payouts/disbursements, or movement of funds |

**Timeline for expansion:** No committed date. Expansion is a **separate phase** triggered only by a concrete pilot requirement and re-scoping exercise — not mid-build scope creep (**SCOPE-4**).

---

## Reasoning

1. The entire ingestion pipeline, webhook validators, and Daraja auth client are M-Pesa-specific today.
2. Pilot positioning targets SACCOs and SMEs whose primary payment pain is M-Pesa reconciliation.
3. Building Airtel Money or bank connectors speculatively would delay pilot value without validated demand.

## Settlement and money-movement boundary

PesaGuard currently records provider callbacks and compares them with internal ledger records. It does **not** execute or confirm settlements, initiate B2C payouts or refunds, transfer funds, maintain a settlement ledger, or expose a provider transaction-status query workflow. Refund/reversal matching in reconciliation is classification against supplied records, not a request to move money.

B2C result callbacks are structurally validated, but ingestion rejects them until an explicit provider-account mapping is available. The generic Daraja OAuth/HTTP client is not a transaction-status or payout implementation. The `Settlement` shape in `schemas/reconciliation-1.0.json` is a schema definition only; there is no corresponding settlement persistence model, executable API, or worker workflow.

Do not describe these capabilities as implemented or infer settlement state from a matched reconciliation result. Any settlement or money-movement feature requires a separately scoped design, provider-specific test contract, accounting invariants, and test-only validation before implementation.

---

## Customer-facing statement (SCOPE-2)

> PesaGuard currently ingests supported **M-Pesa (Daraja) C2B/STK callbacks** and reconciles them against internal records. B2C result callbacks are not ingested, and PesaGuard does not initiate payouts, refunds, or settlements or move funds. Airtel Money, bank transfers, and other payment methods are not included in reconciliation or alerting until a future release. Contact us if multi-rail support is a hard requirement — that becomes a separate scoping conversation.

This statement appears in:

- Customer onboarding and FAQ content is maintained outside this technical documentation tree.

---

## Future expansion path (SCOPE-3)

If a pilot customer requires multi-payment-method reconciliation, evaluate reusing the existing **`BaseConnector`** pattern as a **`PaymentSourceConnector`** abstraction:

```python
# Conceptual — not implemented until a customer requirement justifies it
class PaymentSourceConnector(ABC):
    @abstractmethod
    def fetch_recent_payments(self, since_minutes: int) -> Iterable[Dict[str, Any]]:
        """Normalized: payment_ref, amount, payer_id, timestamp, rail, status"""
        ...
```

| Existing pattern | Reuse for payment rails |
|---|---|
| `BaseConnector.fetch_recent_records()` | Same normalized-record idea for internal ledger |
| `ConnectorRegistry.from_env()` | Per-tenant, per-rail connector registration |
| Reconciliation job matching logic | Should compare on normalized fields, not M-Pesa-specific keys |

**Do not refactor core reconciliation to this pattern until a paying customer needs a second rail.** Document the evaluation here; implement when scoped.

### Rails under consideration (unordered, uncommitted)

| Rail | Integration path | Effort estimate | Status |
|---|---|---|---|
| Airtel Money | Partner API / file export | Medium–High | Not started |
| Bank transfers | Statement import / open banking | High | Not started |
| Additional M-Pesa products | Daraja APIs already partially integrated | Low–Medium | In scope via Daraja |

---

## When a pilot asks for multi-rail support (SCOPE-4)

1. **Do not** silently add connectors during the current build.
2. Schedule a scoping call using Phase 0 discovery questions:
   - *"Besides M-Pesa, do you take payments through Airtel Money, bank transfer, or anything else we'd need to reconcile?"*
3. Produce a separate phase proposal: connectors needed, timeline, pricing, residency impact.
4. Update this document with the agreed expansion plan before development starts.

---

## Validation

Kiswahili scope statements and customer docs should be reviewed by a Kiswahili-speaking user or pilot candidate before being treated as final (*pending real-world input*).

---

## Related files

- `pesaguard_backend_pipeline/base_connector.py` — internal ledger connector pattern
- `frontend/locales/en.json` / `sw.json` — dashboard scope banner
- Customer onboarding copy is maintained outside this technical documentation tree.
