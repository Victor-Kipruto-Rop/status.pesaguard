package com.pesaguard.backend.tenancy;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Small tenant-keyed cache foundation. Keys always contain the tenant ID;
 * callers must not use an unscoped application key. It is intentionally
 * bounded to avoid turning a local cache into an unbounded data store.
 */
@Component
public class TenantAwareCache {

    private static final int MAX_ENTRIES = 2048;
    private final Map<CacheKey, Object> values = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T get(UUID tenantId, String namespace, String key, Class<T> type) {
        Object value = values.get(new CacheKey(tenantId, namespace, key));
        return type.isInstance(value) ? (T) value : null;
    }

    public void put(UUID tenantId, String namespace, String key, Object value) {
        if (values.size() >= MAX_ENTRIES) {
            values.entrySet().stream().findFirst().ifPresent(entry -> values.remove(entry.getKey(), entry.getValue()));
        }
        values.put(new CacheKey(tenantId, namespace, key), value);
    }

    public void invalidate(UUID tenantId) {
        values.entrySet().removeIf(entry -> entry.getKey().tenantId().equals(tenantId));
    }

    private record CacheKey(UUID tenantId, String namespace, String key) {
        @Override
        public String toString() {
            return tenantId + ":" + namespace + ":" + key;
        }
    }
}