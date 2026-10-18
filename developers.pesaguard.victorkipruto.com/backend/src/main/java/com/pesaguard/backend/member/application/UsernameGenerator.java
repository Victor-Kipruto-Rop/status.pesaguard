package com.pesaguard.backend.member.application;

import java.util.Locale;

import com.pesaguard.backend.member.infrastructure.UserAccountRepository;

/** Builds an available username from the email local-part. */
public final class UsernameGenerator {

    private UsernameGenerator() {
    }

    public static String generate(String email, UserAccountRepository users) {
        String localPart = email.substring(0, email.indexOf('@')).toLowerCase(Locale.ROOT);
        String base = localPart.replaceAll("[^a-z0-9._-]", "")
                .replaceAll("^[._-]+|[._-]+$", "");
        if (base.length() < 3) {
            base = "developer";
        }
        base = base.substring(0, Math.min(base.length(), 32));

        if (!users.existsByUsernameIgnoreCase(base)) {
            return base;
        }

        for (int suffix = 2; suffix < Integer.MAX_VALUE; suffix++) {
            String ending = "-" + suffix;
            String candidate = base.substring(0, Math.min(base.length(), 32 - ending.length())) + ending;
            if (!users.existsByUsernameIgnoreCase(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to generate a unique username");
    }
}
