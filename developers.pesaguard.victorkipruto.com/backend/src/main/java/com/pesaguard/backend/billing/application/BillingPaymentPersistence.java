package com.pesaguard.backend.billing.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pesaguard.backend.billing.domain.BillingPayment;
import com.pesaguard.backend.billing.infrastructure.BillingPaymentRepository;
import com.pesaguard.backend.common.exception.ResourceNotFoundException;

@Service
public class BillingPaymentPersistence {

    private final BillingPaymentRepository paymentRepository;

    public BillingPaymentPersistence(BillingPaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BillingPayment create(BillingPayment payment) {
        return paymentRepository.saveAndFlush(payment);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BillingPayment attachProviderResult(UUID paymentId, String reference, String checkoutUrl) {
        BillingPayment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment"));
        payment.attachProviderResult(reference, checkoutUrl);
        return paymentRepository.saveAndFlush(payment);
    }
}
