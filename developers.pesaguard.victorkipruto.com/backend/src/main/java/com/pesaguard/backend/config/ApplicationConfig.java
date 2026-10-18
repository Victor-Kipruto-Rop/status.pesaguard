package com.pesaguard.backend.config;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Map;

import javax.crypto.SecretKey;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class ApplicationConfig {

    /** Argon2 reference defaults, pinned rather than configured. */
    private static final int ARGON2_SALT_LENGTH = 16;
    private static final int ARGON2_HASH_LENGTH = 32;

    @Bean
    SecureRandom secureRandom() {
        return new SecureRandom();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Argon2id by default, upgrading legacy BCrypt hashes on successful sign-in.
     *
     * <p>Argon2id is memory-hard, so an attacker with GPU or ASIC advantage gains
     * far less per dollar than against BCrypt, which is CPU-bound only. The default
     * is Argon2id rather than a BCrypt/Argon2id pair because the migration path is
     * the one exception below, not the steady state.
     *
     * <p><b>Upgrade on login.</b> The BCrypt branch is the only reason it exists. A
     * hash written before the migration still verifies, and because the BCrypt
     * encoder reports that its own parameters are stale via
     * {@code upgradeEncoding}, every successful sign-in silently rewrites the stored
     * hash to Argon2id. That converts the whole user base over as people log in,
     * without a batch job that would have to handle accounts nobody ever returns to,
     * and without a second credential.
     */
    @Bean
    PasswordEncoder passwordEncoder(ApplicationProperties properties) {
        // NOTE the parameter order. Spring Security 7 declares this constructor as
        // (saltLength, hashLength, parallelism, memory, iterations) -- not the
        // (iterations, memory, parallelism, saltLength, hashLength) that earlier
        // versions and most documentation imply. Passing the OWASP values in the
        // assumed order silently yields saltLength=2, hashLength=19456,
        // memory=16 KiB and iterations=32: a ~25,000 character "hash" that fits no
        // password column, computed over a memory cost low enough to be worthless.
        // Verified against the library rather than read off the signature.
        Argon2PasswordEncoder argon2 = new Argon2PasswordEncoder(
                ARGON2_SALT_LENGTH,
                ARGON2_HASH_LENGTH,
                properties.security().argon2Parallelism(),
                properties.security().argon2MemoryKilobytes(),
                properties.security().argon2Iterations());
        // Both halves named explicitly. "argon2" writes; "bcrypt" is accepted on
        // read only, so pre-migration hashes still verify and are upgraded in
        // place on each successful sign-in rather than by a batch migration that
        // would strand every account nobody logs into again.
        //
        // Wrapped rather than used directly: DelegatingPasswordEncoder keys off a
        // "{id}" prefix, and the hashes written before this change were produced by
        // a bare BCryptPasswordEncoder, which stores no prefix. Passing one of those
        // to DelegatingPasswordEncoder throws IllegalArgumentException("no default
        // password encoder configured") rather than verifying, so the documented
        // migration path would fail exactly where it is supposed to work -- and the
        // throw happens inside authentication, where it surfaces as a 500 for a user
        // who typed the right password. No migration adds the prefix, and none ever
        // wrote one.
        return new LegacyTolerantPasswordEncoder(new DelegatingPasswordEncoder("argon2", Map.of(
                "argon2", argon2,
                "bcrypt", new BCryptPasswordEncoder(properties.security().bcryptStrength()))));
    }

    /**
     * Delegates, except that an unprefixed stored hash is read as legacy BCrypt.
     *
     * <p>Only {@code $2a$}/{@code $2b$}/{@code $2y$} values are treated this way;
     * anything else without a prefix is still an error, so a genuinely corrupt or
     * foreign hash fails loudly instead of being silently mis-decoded. New hashes
     * always carry the {@code {argon2}} prefix the delegate writes.
     */
    private static final class LegacyTolerantPasswordEncoder implements PasswordEncoder {

        private static final String[] BCRYPT_PREFIXES = { "$2a$", "$2b$", "$2y$" };

        private final PasswordEncoder delegate;

        LegacyTolerantPasswordEncoder(PasswordEncoder delegate) {
            this.delegate = delegate;
        }

        private static boolean isUnprefixedBcrypt(String encoded) {
            if (encoded == null) {
                return false;
            }
            for (String prefix : BCRYPT_PREFIXES) {
                if (encoded.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String encode(CharSequence rawPassword) {
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            if (isUnprefixedBcrypt(encodedPassword)) {
                // Bridge into the delegate's own bcrypt entry so the cost and any
                // future change to it stay in one place.
                return delegate.matches(rawPassword, "{bcrypt}" + encodedPassword);
            }
            return delegate.matches(rawPassword, encodedPassword);
        }

        @Override
        public boolean upgradeEncoding(String encodedPassword) {
            if (isUnprefixedBcrypt(encodedPassword)) {
                // True is what drives the re-hash to Argon2id on successful sign-in.
                return true;
            }
            return delegate.upgradeEncoding(encodedPassword);
        }
    }

    @Bean
    SecretKey credentialHmacKey(ApplicationProperties properties) {
        properties.security().validateKeyMaterial();
        byte[] key = java.util.Base64.getDecoder().decode(properties.security().credentialHmacKey());
        return new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256");
    }

    /**
     * Key for internal operator tokens.
     *
     * <p><b>Deliberately a separate key from the credential and audit keys.</b>
     * Sharing one would mean a developer API key presented as an operator token is
     * a matter of formatting, and the entire separation between the developer API
     * and internal administration rests on those tokens being unrelated.
     *
     * <p>Validates at startup rather than lazily: an operator key that is missing or
     * wrong must stop the deployment, not surface as an authentication failure
     * during an incident.
     */
    @Bean
    SecretKey operatorHmacKey(ApplicationProperties properties) {
        String encoded = properties.platform().operatorHmacKey();
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException(
                    "PESAGUARD_OPERATOR_HMAC_KEY is required for internal administration");
        }
        byte[] key;
        try {
            key = java.util.Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException(
                    "PESAGUARD_OPERATOR_HMAC_KEY must be base64", malformed);
        }
        if (key.length < 32) {
            throw new IllegalStateException(
                    "PESAGUARD_OPERATOR_HMAC_KEY must decode to at least 32 bytes");
        }
        return new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256");
    }

    @Bean
    SecretKey auditHmacKey(ApplicationProperties properties) {
        byte[] key = java.util.Base64.getDecoder().decode(properties.security().auditHmacKey());
        return new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256");
    }
}
