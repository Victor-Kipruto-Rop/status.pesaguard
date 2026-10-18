package com.pesaguard.backend.billing.api;

import java.util.List;

public record BillingOverviewView(
        List<BillingInvoiceView> invoices,
        List<BillingInvoiceRequestView> invoiceRequests,
        List<BillingPaymentView> payments,
        List<BillingProviderOption> providers,
        String manualInstructions) {

    public BillingOverviewView {
        invoices = invoices == null ? List.of() : List.copyOf(invoices);
        invoiceRequests = invoiceRequests == null ? List.of() : List.copyOf(invoiceRequests);
        payments = payments == null ? List.of() : List.copyOf(payments);
        providers = providers == null ? List.of() : List.copyOf(providers);
    }
}
