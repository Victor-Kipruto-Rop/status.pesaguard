package com.pesaguard.backend.tenancy;

import java.util.Optional;

public final class TenantContextHolder {

    private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();

    private TenantContextHolder() {
    }

    public static void set(TenantContext context) {
        if (context == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(context);
        }
    }

    public static Optional<TenantContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static TenantContext require() {
        TenantContext context = CURRENT.get();
        if (context == null) {
            throw new IllegalStateException("Tenant context is not available");
        }
        return context;
    }

    public static void clear() {
        CURRENT.remove();
    }
}