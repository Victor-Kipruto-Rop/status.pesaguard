package com.pesaguard.backend.credentials.api;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

public final class ScopeCodec {

    private ScopeCodec() {
    }

    public static String encode(Set<String> scopes) {
        return new TreeSet<>(scopes).stream().reduce((left, right) -> left + "," + right).orElse("");
    }

    public static Set<String> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return Set.of();
        }
        return Set.copyOf(Arrays.asList(encoded.split(",", -1)));
    }
}
