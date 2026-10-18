package com.pesaguard.backend.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextAndCacheTest {

    private final TenantAwareCache cache = new TenantAwareCache();
    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
    }

    @Test
    void cacheEntriesAreIsolatedPerTenant() {
        cache.put(tenantA, "organization-view", "current", "organization-a");

        assertThat(cache.get(tenantA, "organization-view", "current", String.class)).isEqualTo("organization-a");
        assertThat(cache.get(tenantB, "organization-view", "current", String.class)).isNull();
    }

    @Test
    void invalidatingOneTenantKeepsTheOther() {
        cache.put(tenantA, "organization-view", "current", "organization-a");
        cache.put(tenantB, "organization-view", "current", "organization-b");

        cache.invalidate(tenantA);

        assertThat(cache.get(tenantA, "organization-view", "current", String.class)).isNull();
        assertThat(cache.get(tenantB, "organization-view", "current", String.class)).isEqualTo("organization-b");
    }

    @Test
    void cacheReturnsNullWhenTheStoredTypeDoesNotMatch() {
        cache.put(tenantA, "organization-view", "current", 42);

        assertThat(cache.get(tenantA, "organization-view", "current", String.class)).isNull();
    }

    @Test
    void tenantContextExposesTenantAsAnAliasOfTheOrganization() {
        TenantContext context = new TenantContext(
                tenantA, tenantA, UUID.randomUUID(), UUID.randomUUID(), null, null,
                UUID.randomUUID().toString(), "203.0.113.9", "unit-test");

        TenantContextHolder.set(context);

        assertThat(TenantContextHolder.require().tenantId()).isEqualTo(tenantA);
        assertThat(TenantContextHolder.require().organizationId()).isEqualTo(tenantA);
        assertThat(TenantContextHolder.require().actorId()).isEqualTo(context.actorId());
        assertThat(TenantContextHolder.require().projectId()).isNull();

        TenantContextHolder.clear();
        assertThat(TenantContextHolder.current()).isEmpty();
    }

    @Test
    void settingANullContextClearsTheTenant() {
        TenantContextHolder.set(new TenantContext(
                tenantA, tenantA, UUID.randomUUID(), UUID.randomUUID(), null, null,
                UUID.randomUUID().toString(), null, null));
        TenantContextHolder.set(null);

        assertThat(TenantContextHolder.current()).isEmpty();
    }
}