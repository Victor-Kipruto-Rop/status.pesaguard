package com.pesaguard.backend.security.authentication;

import java.text.Normalizer;
import java.util.Locale;

public final class SlugGenerator {

    private SlugGenerator() {
    }

    public static String slug(String value) {
        String normalized = Normalizer.normalize(value.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (normalized.isBlank()) {
            return "organization";
        }
        return normalized.substring(0, Math.min(normalized.length(), 70));
    }
}
