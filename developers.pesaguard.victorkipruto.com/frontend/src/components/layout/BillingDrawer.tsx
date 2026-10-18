import { ValidatedForm } from "../forms/ValidatedForm";
import { getUserMessage } from "../../lib/errors";
import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { AlertCircle, CircleHelp, ExternalLink, RefreshCw } from "lucide-react";
import { apiData } from "../../lib/api";
import { generateIdempotencyKey } from "../../lib/idempotency";

type Invoice = {
  id: string;
  invoiceNumber: string;
  description: string;
  amountMinor: number;
  currency: string;
  status: string;
  dueAt: string | null;
  issuedAt: string;
  paidAt: string | null;
};

type InvoiceRequest = {
  id: string;
  description: string;
  status: string;
  createdAt: string;
  reviewedAt: string | null;
};

type Payment = {
  id: string;
  invoiceId: string;
  provider: string;
  status: string;
  providerReference: string | null;
  checkoutUrl: string | null;
  phoneLastFour: string | null;
  createdAt: string;
  paidAt: string | null;
};

type Provider = {
  id: string;
  label: string;
  available: boolean;
  requiresPhone: boolean;
};

type BillingOverview = {
  invoices: Invoice[];
  invoiceRequests: InvoiceRequest[];
  payments: Payment[];
  providers: Provider[];
  manualInstructions: string | null;
};

interface BillingDrawerProps {
  connected: boolean;
}

function formatAmount(amountMinor: number, currency: string): string {
  return new Intl.NumberFormat(undefined, { style: "currency", currency }).format(amountMinor / 100);
}

export function BillingDrawer({ connected }: BillingDrawerProps) {
  const [overview, setOverview] = useState<BillingOverview | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [description, setDescription] = useState("");
  const [phoneNumber, setPhoneNumber] = useState("");
  const [selectedProviders, setSelectedProviders] = useState<Record<string, string>>({});
  const [busyAction, setBusyAction] = useState("");
  const [notice, setNotice] = useState("");

  const loadOverview = useCallback(async (signal?: AbortSignal) => {
    setLoading(true);
    setError("");
    try {
      const result = await apiData<BillingOverview>("/api/v1/billing", { signal });
      if (!result || !Array.isArray(result.invoices) || !Array.isArray(result.providers)
        || !Array.isArray(result.payments) || !Array.isArray(result.invoiceRequests)) {
        throw new Error("The billing API returned an invalid response.");
      }
      setOverview(result);
      setSelectedProviders((current) => {
        const next = { ...current };
        for (const invoice of result.invoices) {
          if (!next[invoice.id]) {
            next[invoice.id] = result.providers.find((provider) => provider.available && provider.id !== "MANUAL")?.id
              ?? result.providers.find((provider) => provider.available)?.id
              ?? "";
          }
        }
        return next;
      });
    } catch (cause) {
      if (!signal?.aborted) setError(getUserMessage(cause, "Unable to load billing records."));
    } finally {
      if (!signal?.aborted) setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!connected) {
      setOverview(null);
      return;
    }
    const controller = new AbortController();
    void loadOverview(controller.signal);
    return () => controller.abort();
  }, [connected, loadOverview]);

  const availableProviders = useMemo(
    () => overview?.providers.filter((provider) => provider.available) ?? [],
    [overview],
  );

  async function requestInvoice(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusyAction("invoice-request");
    setError("");
    setNotice("");
    try {
      await apiData<InvoiceRequest>("/api/v1/billing/invoice-requests", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ description: description.trim() }),
      });
      setDescription("");
      setNotice("Your invoice request was submitted.");
      await loadOverview();
    } catch (cause) {
      setError(getUserMessage(cause, "Unable to submit the invoice request."));
    } finally {
      setBusyAction("");
    }
  }

  async function retryStripeCheckout(paymentId: string) {
    setBusyAction(paymentId);
    setError("");
    setNotice("");
    try {
      const payment = await apiData<Payment>(`/api/v1/billing/payments/${paymentId}/retry`, { method: "POST" });
      setNotice(payment.checkoutUrl ? "Secure checkout is ready." : "The payment provider has not returned a checkout link.");
      await loadOverview();
    } catch (cause) {
      setError(getUserMessage(cause, "Unable to retry Stripe checkout."));
    } finally {
      setBusyAction("");
    }
  }

  async function startPayment(invoice: Invoice) {
    const provider = selectedProviders[invoice.id];
    if (!provider) return;
    setBusyAction(invoice.id);
    setError("");
    setNotice("");
    try {
      const payment = await apiData<Payment>(`/api/v1/billing/invoices/${invoice.id}/payments`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Idempotency-Key": generateIdempotencyKey(),
        },
        body: JSON.stringify({
          provider,
          phoneNumber: overview?.providers.find((option) => option.id === provider)?.requiresPhone
            ? phoneNumber.trim()
            : undefined,
        }),
      });
      setNotice(payment.checkoutUrl ? "Payment session created. Continue using the secure provider link." : "Payment request created.");
      await loadOverview();
    } catch (cause) {
      await loadOverview();
      setError(getUserMessage(cause, "Unable to start payment."));
    } finally {
      setBusyAction("");
    }
  }

  async function refreshPayment(paymentId: string) {
    setBusyAction(paymentId);
    setError("");
    try {
      await apiData<Payment>(`/api/v1/billing/payments/${paymentId}/refresh`, { method: "POST" });
      await loadOverview();
    } catch (cause) {
      setError(getUserMessage(cause, "Unable to refresh payment status."));
    } finally {
      setBusyAction("");
    }
  }

  async function cancelManualPayment(paymentId: string) {
    setBusyAction(paymentId);
    setError("");
    setNotice("");
    try {
      await apiData<Payment>(`/api/v1/billing/payments/${paymentId}/cancel`, { method: "POST" });
      setNotice("Manual settlement was cancelled.");
      await loadOverview();
    } catch (cause) {
      setError(getUserMessage(cause, "Unable to cancel manual settlement."));
    } finally {
      setBusyAction("");
    }
  }

  if (!connected) {
    return (
      <div className="navigation-drawer-notice">
        <span className="navigation-drawer-notice-icon"><CircleHelp size={18} /></span>
        <p>Sign in to load this organization’s invoices, invoice requests, and payment options.</p>
      </div>
    );
  }

  return (
    <div className="billing-drawer-content">
      <div className="billing-drawer-toolbar">
        <span>Organization billing records</span>
        <button className="billing-action-button" type="button" disabled={loading} onClick={() => void loadOverview()}>
          <RefreshCw size={14} aria-hidden="true" /> Refresh
        </button>
      </div>

      {error && <p className="form-error" role="alert"><AlertCircle size={15} />{error}</p>}
      {notice && <p className="billing-notice" role="status">{notice}</p>}
      {loading && !overview && <p role="status">Loading billing records…</p>}

      {overview && (
        <>
          <section className="billing-section" aria-labelledby="billing-invoices-title">
            <h3 id="billing-invoices-title">Invoices</h3>
            {overview.invoices.length === 0 ? (
              <p className="billing-empty">No invoices have been issued to this organization.</p>
            ) : overview.invoices.map((invoice) => {
              const activePayment = overview.payments.find((payment) =>
                payment.invoiceId === invoice.id && ["PENDING", "AWAITING_MANUAL"].includes(payment.status));
              const provider = overview.providers.find((option) => option.id === selectedProviders[invoice.id]);
              return (
                <article className="billing-record" key={invoice.id}>
                  <div className="billing-record-heading">
                    <div><strong>{invoice.invoiceNumber}</strong><span>{invoice.status}</span></div>
                    <strong>{formatAmount(invoice.amountMinor, invoice.currency)}</strong>
                  </div>
                  <p>{invoice.description}</p>
                  <small>{invoice.dueAt ? `Due ${new Date(invoice.dueAt).toLocaleDateString()}` : "No due date set"} · Issued {new Date(invoice.issuedAt).toLocaleDateString()}</small>
                  {invoice.status === "OPEN" && !activePayment && availableProviders.length > 0 && (
                    <div className="billing-payment-controls">
                      <label>
                        Payment option
                        <select value={selectedProviders[invoice.id] ?? ""} onChange={(event) =>
                          setSelectedProviders((current) => ({ ...current, [invoice.id]: event.target.value }))}>
                          {availableProviders.map((option) => <option value={option.id} key={option.id}>{option.label}</option>)}
                        </select>
                      </label>
                      {provider?.requiresPhone && (
                        <label>
                          Mobile money number
                          <input type="tel" autoComplete="tel" value={phoneNumber} onChange={(event) => setPhoneNumber(event.target.value)} placeholder="e.g. 2547…" />
                        </label>
                      )}
                      <button type="button" className="billing-primary-button" disabled={busyAction === invoice.id || !provider}
                        onClick={() => void startPayment(invoice)}>
                        {busyAction === invoice.id ? "Starting…" : provider?.id === "MANUAL" ? "Record manual settlement" : "Continue to payment"}
                      </button>
                    </div>
                  )}
                  {invoice.status === "OPEN" && !activePayment && availableProviders.length === 0
                    && <p className="billing-empty">No payment provider is configured. Contact support to arrange payment.</p>}
                  {activePayment && (
                    <div className="billing-pending">
                      Payment {activePayment.status.toLowerCase().replaceAll("_", " ")} using {activePayment.provider}.
                      {activePayment.status === "AWAITING_MANUAL" && (
                        <button className="billing-action-button" type="button" disabled={busyAction === activePayment.id}
                          onClick={() => void cancelManualPayment(activePayment.id)}>Cancel manual settlement</button>
                      )}
                    </div>
                  )}
                </article>
              );
            })}
          </section>

          <section className="billing-section" aria-labelledby="billing-payment-options-title">
            <h3 id="billing-payment-options-title">Payment options</h3>
            {overview.providers.map((provider) => (
              <div className="billing-provider" key={provider.id}>
                <span>{provider.label}</span>
                <small>{provider.available ? "Available" : "Not configured"}</small>
              </div>
            ))}
            {overview.manualInstructions && (
              <div className="billing-manual-instructions">
                <strong>Manual settlement instructions</strong>
                <p>{overview.manualInstructions}</p>
              </div>
            )}
          </section>

          <section className="billing-section" aria-labelledby="billing-requests-title">
            <h3 id="billing-requests-title">Invoice requests</h3>
            <ValidatedForm className="billing-request-form" onSubmit={(event) => void requestInvoice(event)}>
              <label htmlFor="billing-request-description">What would you like invoiced?</label>
              <textarea id="billing-request-description" value={description} maxLength={1000} required
                onChange={(event) => setDescription(event.target.value)} rows={3} />
              <button type="submit" className="billing-primary-button" disabled={busyAction === "invoice-request" || !description.trim()}>
                {busyAction === "invoice-request" ? "Submitting…" : "Request an invoice"}
              </button>
            </ValidatedForm>
            {overview.invoiceRequests.length === 0 ? <p className="billing-empty">No invoice requests have been submitted.</p>
              : overview.invoiceRequests.map((request) => (
                <article className="billing-record billing-record--compact" key={request.id}>
                  <div className="billing-record-heading"><strong>{request.description}</strong><span>{request.status}</span></div>
                  <small>Submitted {new Date(request.createdAt).toLocaleDateString()}</small>
                </article>
              ))}
          </section>

          <section className="billing-section" aria-labelledby="billing-payments-title">
            <h3 id="billing-payments-title">Payment activity</h3>
            {overview.payments.length === 0 ? <p className="billing-empty">No payment attempts have been recorded.</p>
              : overview.payments.map((payment) => (
                <article className="billing-record billing-record--compact" key={payment.id}>
                  <div className="billing-record-heading">
                    <strong>{payment.provider}</strong><span>{payment.status}</span>
                  </div>
                  <small>Created {new Date(payment.createdAt).toLocaleString()}{payment.phoneLastFour ? ` · Phone ending ${payment.phoneLastFour}` : ""}</small>
                  {payment.checkoutUrl && <a href={payment.checkoutUrl} target="_blank" rel="noreferrer">Open secure checkout <ExternalLink size={13} /></a>}
                  {payment.provider === "STRIPE" && payment.status === "PENDING" && !payment.providerReference && (
                    <button className="billing-action-button" type="button" disabled={busyAction === payment.id}
                      onClick={() => void retryStripeCheckout(payment.id)}>Retry secure checkout</button>
                  )}
                  {["PENDING"].includes(payment.status) && (
                    <button className="billing-action-button" type="button" disabled={busyAction === payment.id}
                      onClick={() => void refreshPayment(payment.id)}>Refresh status</button>
                  )}
                </article>
              ))}
          </section>
        </>
      )}
    </div>
  );
}
