package com.pesaguard.backend.serviceaccount.api;

public record CreatedServiceAccountView(ServiceAccountView serviceAccount, String clientSecret) {
}
