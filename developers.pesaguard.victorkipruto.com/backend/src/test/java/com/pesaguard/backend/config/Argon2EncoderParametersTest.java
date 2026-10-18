package com.pesaguard.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Pins the Argon2 parameter mapping in {@link ApplicationConfig#passwordEncoder}.
 *
 * <p>Spring Security 7's five-argument {@code Argon2PasswordEncoder} constructor is
 * declared as {@code (saltLength, hashLength, parallelism, memory, iterations)}. The
 * widely-cited {@code (iterations, memory, parallelism, saltLength, hashLength)} order
 * does not exist on this class, and Java will not complain if the two are confused:
 * every argument is an {@code int}, so a swapped call compiles, boots, and passes a
 * naive "it encoded something" assertion.
 *
 * <p>It was in fact confused. Passing the OWASP values in the assumed order produced
 * {@code saltLength=2, hashLength=19456, memory=16 KiB, iterations=32} -- an encoded
 * hash around 25,000 characters long that overflowed {@code users.password_hash} and
 * made every registration fail with a 409, while advertising a memory cost low enough
 * that the KDF provided no meaningful protection.
 *
 * <p>These assertions read the parameters back out of an actual encoded hash, so a
 * future reordering is caught by the output rather than by inspection.
 */
class Argon2EncoderParametersTest {

    /** OWASP's second recommended option, and the production default. */
    private static final int OWASP_MEMORY_KIB = 19_456;
    private static final int OWASP_ITERATIONS = 2;
    private static final int OWASP_PARALLELISM = 1;

    /**
     * Ceiling for an encoded Argon2id hash. A correctly configured encoder emits
     * roughly 95 characters; the misconfigured one emitted about 25,000. 255 is the
     * column width, so anything approaching it means the hash length parameter is
     * receiving a cost value again.
     */
    private static final int MAX_SANE_HASH_LENGTH = 255;

    private static PasswordEncoder encoderWith(int memoryKib, int iterations, int parallelism) {
        ApplicationProperties.Security security = new ApplicationProperties.Security(
                List.of(URI.create("https://developers.pesaguard.co.ke")),
                Duration.ofHours(8),
                Duration.ofDays(30),
                Duration.ofMinutes(30),
                "Y3JlZGVudGlhbC1rZXktMzItYnl0ZXMh",
                "YXVkaXQta2V5LTMyLWJ5dGVzISEhISE=",
                true,
                memoryKib,
                iterations,
                parallelism,
                10,
                3,
                10,
                Duration.ofMinutes(15),
                Duration.ofDays(7),
                30,
                Duration.ofMinutes(15));
        ApplicationProperties properties = new ApplicationProperties(
                security,
                new ApplicationProperties.Platform("b3BlcmF0b3Ita2V5LTMyLWJ5dGVzISEhISEh"),
                new ApplicationProperties.JsonWebToken(
                        "pesaguard-developer-platform",
                        "pesaguard-developer-platform",
                        "test-v1",
                        com.pesaguard.backend.security.TestKeys.PRIVATE_KEY_BASE64,
                        com.pesaguard.backend.security.TestKeys.PUBLIC_KEY_BASE64,
                        Duration.ofMinutes(5)));
        return new ApplicationConfig().passwordEncoder(properties);
    }

    private static String encodeWith(int memoryKib) {
        return encoderWith(memoryKib, OWASP_ITERATIONS, OWASP_PARALLELISM)
                .encode("correct horse battery staple");
    }

    @Test
    @DisplayName("configured memory, iteration and parallelism values land in the encoded hash")
    void parametersAreMappedToTheRightSlots() {
        // The "{argon2}" id prefix is written by DelegatingPasswordEncoder; the cost
        // parameters live in the encoded hash behind it.
        String hash = encodeWith(OWASP_MEMORY_KIB);

        assertThat(hash)
                .as("new hashes must be Argon2id")
                .startsWith("{argon2}$argon2id$")
                .contains("m=" + OWASP_MEMORY_KIB)
                .contains("t=" + OWASP_ITERATIONS)
                .contains("p=" + OWASP_PARALLELISM);
    }


    @Test
    @DisplayName("hash length stays within the column width")
    void hashFitsThePasswordColumn() {
        assertThat(encodeWith(OWASP_MEMORY_KIB).length())
                .as("a hash that overflows password_hash makes registration impossible")
                .isLessThanOrEqualTo(MAX_SANE_HASH_LENGTH);
    }

    @Test
    @DisplayName("a raised memory cost does not inflate the hash length")
    void memoryCostDoesNotBecomeHashLength() {
        String low = encodeWith(65_536);
        String high = encodeWith(262_144);

        // Memory cost belongs in the work factor, never in the output. Comparing the
        // payload rather than the whole string matters: "m=262144" is one digit
        // longer than "m=65536", so the encoded strings legitimately differ by one
        // character for a reason that has nothing to do with the hash.
        assertThat(payloadOf(high)).hasSameSizeAs(payloadOf(low));
        assertThat(high).contains("m=262144");
        assertThat(low).contains("m=65536");
    }

    /** The salt and digest, with the {@code $argon2id$v=19$m=..,t=..,p=..$} header removed. */
    private static String payloadOf(String hash) {
        return hash.substring(hash.lastIndexOf('$') + 1);
    }

    @Test
    @DisplayName("a per-hash salt is applied")
    void saltIsApplied() {
        String first = encodeWith(OWASP_MEMORY_KIB);
        String second = encodeWith(OWASP_MEMORY_KIB);

        assertThat(first)
                .as("a constant output would mean no salt is being applied")
                .isNotEqualTo(second);
        assertThat(first.length()).isEqualTo(second.length());
    }

    @Test
    @DisplayName("legacy BCrypt hashes still verify and are flagged for upgrade")
    void bcryptHashesStillVerifyAndUpgrade() {
        PasswordEncoder encoder = encoderWith(OWASP_MEMORY_KIB, OWASP_ITERATIONS, OWASP_PARALLELISM);
        // Exactly what the pre-migration code stored: a bare BCrypt hash with no
        // "{bcrypt}" prefix, because nothing ever wrote one.
        String legacy = new BCryptPasswordEncoder(10).encode("correct horse battery staple");

        assertThat(encoder.matches("correct horse battery staple", legacy))
                .as("a user who typed the right password must not be locked out by the migration")
                .isTrue();
        assertThat(encoder.upgradeEncoding(legacy))
                .as("BCrypt hashes must be rewritten to Argon2id on successful sign-in")
                .isTrue();

        // The re-encoded value must be Argon2id, so the next sign-in takes the
        // default path and stops consulting the legacy branch.
        String upgraded = encoder.encode("correct horse battery staple");
        assertThat(upgraded).startsWith("{argon2}");
        assertThat(encoder.upgradeEncoding(upgraded)).isFalse();
    }

    @Test
    @DisplayName("a prefixed legacy BCrypt hash still verifies")
    void prefixedBcryptHashesStillVerify() {
        PasswordEncoder encoder = encoderWith(OWASP_MEMORY_KIB, OWASP_ITERATIONS, OWASP_PARALLELISM);
        String legacy = "{bcrypt}" + new BCryptPasswordEncoder(10).encode("correct horse battery staple");

        assertThat(encoder.matches("correct horse battery staple", legacy)).isTrue();
        assertThat(encoder.upgradeEncoding(legacy)).isTrue();
    }

    @Test
    @DisplayName("an unrecognised stored hash is refused rather than mis-decoded")
    void unknownHashFormatsAreRefused() {
        PasswordEncoder encoder = encoderWith(OWASP_MEMORY_KIB, OWASP_ITERATIONS, OWASP_PARALLELISM);

        assertThatThrownBy(() -> encoder.matches("correct horse battery staple", "not-a-hash"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> encoder.matches("correct horse battery staple", "{unknown}x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a wrong password does not verify")
    void wrongPasswordIsRejected() {
        PasswordEncoder encoder = encoderWith(OWASP_MEMORY_KIB, OWASP_ITERATIONS, OWASP_PARALLELISM);
        String hash = encoder.encode("correct horse battery staple");

        assertThat(encoder.matches("correct horse battery stapl", hash)).isFalse();
        assertThat(encoder.matches("", hash)).isFalse();
    }
}
