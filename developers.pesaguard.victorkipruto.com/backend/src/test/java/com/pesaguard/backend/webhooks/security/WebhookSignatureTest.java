package com.pesaguard.backend.webhooks.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Webhook signing.
 *
 * <p>The behaviours that matter are the ones an attack depends on: that the
 * signature binds the timestamp, that a stale one stops verifying, and that
 * comparison does not leak through timing.
 */
class WebhookSignatureTest {

    private static final String SECRET = "whsec_test_secret_value";
    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");

    /**
     * The canonical example published in docs/webhooks.md.
     *
     * <p>Pinned so the documented example and the implementation cannot drift
     * apart. An integrator copying this example must get a signature that
     * verifies; if the scheme changes, this test fails first and the docs get
     * updated deliberately rather than silently becoming wrong.
     */
    private static final String CANONICAL_SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
    private static final String CANONICAL_BODY =
            "{\"id\":\"evt_01\",\"type\":\"payment.succeeded\",\"data\":{\"id\":\"pay_123\",\"amount\":5000,\"currency\":\"KES\"}}";
    private static final long CANONICAL_EPOCH = 1712345678L;
    private static final String CANONICAL_HEADER =
            "t=1712345678,v1=4e82adaac59e5124b4db704461ccbe38476325eec69fe913625889eccadd6772";

    /** Well-formed but wrong, so the HMAC check is what rejects it. */
    private static final String FORGED_HEADER =
            "t=1712345678,v1=deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef";

    private WebhookSignature.VerificationResult verify(String secret, String header, String body) {
        return WebhookSignature.verify(secret, header, body, NOW, Duration.ofMinutes(5));
    }

    @Test
    void theCanonicalExampleMatchesTheDocumentedValue() {
        // Generated independently by the Python and Node reference implementations
        // in src/test/resources. If Java disagrees, one of the three is wrong and
        // integrators copying the documentation would be broken.
        assertThat(WebhookSignature.sign(CANONICAL_SECRET, CANONICAL_BODY,
                Instant.ofEpochSecond(CANONICAL_EPOCH)))
                .isEqualTo(CANONICAL_HEADER);
    }

    /**
     * Runs the independent reference implementations and compares them against
     * Java, so the cross-check happens on every build rather than once by hand.
     *
     * <p>Checks the verification path as well as signing. That matters: an earlier
     * version of these reference implementations had a bug that signing never
     * exercised, where the candidate digest was recovered by splitting the header
     * string and therefore included the timestamp. Only comparing outcomes
     * caught it.
     *
     * <p>Skipped when an interpreter is absent, because a signature cross-checked
     * only on one developer machine is not really cross-checked.
     */
    @Test
    void javaAgreesWithTheIndependentReferenceImplementations() throws Exception {
        Path body = Files.createTempFile("pesaguard-webhook-canonical", ".json");
        try {
            // Written as bytes with no trailing newline: the signature covers the
            // body exactly as sent, so a stray newline here would be a different
            // body and a different signature.
            Files.write(body, CANONICAL_BODY.getBytes(StandardCharsets.UTF_8));

            for (String interpreter : new String[] {"python", "node"}) {
                String signed = runReference(interpreter, "sign", CANONICAL_HEADER, body);
                Assumptions.assumeTrue(signed != null,
                        interpreter + " is not available; cross-check skipped");

                assertThat(WebhookSignature.sign(CANONICAL_SECRET, CANONICAL_BODY,
                        Instant.ofEpochSecond(CANONICAL_EPOCH)))
                        .as("%s must agree on signing", interpreter)
                        .isEqualTo(signed);

                // Outcome comparison for fresh, stale and forged headers.
                assertOutcomeAgreement(interpreter, signed, CANONICAL_HEADER,
                        CANONICAL_EPOCH, body, WebhookSignature.VerificationResult.VALID);
                assertOutcomeAgreement(interpreter, signed, CANONICAL_HEADER,
                        CANONICAL_EPOCH + 3600, body, WebhookSignature.VerificationResult.STALE);
                assertOutcomeAgreement(interpreter, signed, FORGED_HEADER,
                        CANONICAL_EPOCH, body, WebhookSignature.VerificationResult.INVALID_SIGNATURE);
            }
        } finally {
            Files.deleteIfExists(body);
        }
    }

    private void assertOutcomeAgreement(String interpreter, String signed, String header,
            long nowEpochSeconds, Path body, WebhookSignature.VerificationResult expected)
            throws Exception {
        String reference = runReference(interpreter, "verify", header, body, nowEpochSeconds);
        Assumptions.assumeTrue(reference != null,
                interpreter + " is not available; cross-check skipped");
        assertThat(WebhookSignature.verify(CANONICAL_SECRET, header, CANONICAL_BODY,
                Instant.ofEpochSecond(nowEpochSeconds), Duration.ofMinutes(5)))
                .as("%s must agree on outcome for now=%d header=%s",
                        interpreter, nowEpochSeconds, header)
                .isEqualTo(expected);
        assertThat(reference)
                .as("%s must reach the same outcome", interpreter)
                .isEqualTo(expected.name());
    }

    /**
     * @param header unused when signing, since the reference derives it itself
     */
    private String runReference(String interpreter, String mode, String header, Path body)
            throws Exception {
        return runReference(interpreter, mode, header, body, null);
    }

    private String runReference(String interpreter, String mode, String header, Path body,
            Long nowEpochSeconds) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add(interpreter);
        command.add(interpreter.equals("python")
                ? "webhook_signature_reference.py" : "webhook_signature_reference.js");
        command.add(mode);
        command.add(CANONICAL_SECRET);
        if ("sign".equals(mode)) {
            command.add("v1");
            command.add(String.valueOf(CANONICAL_EPOCH));
            command.add(body.toString());
        } else {
            command.add(header);
            command.add(String.valueOf(nowEpochSeconds));
            command.add(body.toString());
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(Path.of("src", "test", "resources").toFile());
        builder.redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (java.io.IOException exception) {
            // Interpreter absent: the assumption above will skip the assertion.
            return null;
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .trim();
        process.waitFor();
        return output.isEmpty() ? null : output;
    }

    @Test
    void theCanonicalExampleVerifies() {
        assertThat(WebhookSignature.verify(CANONICAL_SECRET, CANONICAL_HEADER, CANONICAL_BODY,
                Instant.ofEpochSecond(CANONICAL_EPOCH), Duration.ofMinutes(5)))
                .isEqualTo(WebhookSignature.VerificationResult.VALID);
    }

    @Test
    void aFreshSignatureVerifies() {
        String header = WebhookSignature.sign(SECRET, "{\"a\":1}", NOW);

        assertThat(verify(SECRET, header, "{\"a\":1}"))
                .isEqualTo(WebhookSignature.VerificationResult.VALID);
    }

    @Test
    void theHeaderCarriesTimestampAndVersion() {
        String header = WebhookSignature.sign(SECRET, "{}", NOW);

        assertThat(header).startsWith("t=" + NOW.getEpochSecond() + ",");
        assertThat(header).contains(",v1=");
    }

    @Test
    void aDifferentBodyDoesNotVerify() {
        String header = WebhookSignature.sign(SECRET, "{\"amount\":100}", NOW);

        // Changing the body by one character must invalidate: this is what stops
        // an attacker editing a captured payload.
        assertThat(verify(SECRET, header, "{\"amount\":101}"))
                .isEqualTo(WebhookSignature.VerificationResult.INVALID_SIGNATURE);
    }

    @Test
    void aDifferentSecretDoesNotVerify() {
        String header = WebhookSignature.sign(SECRET, "{}", NOW);

        assertThat(verify("another_secret", header, "{}"))
                .isEqualTo(WebhookSignature.VerificationResult.INVALID_SIGNATURE);
    }

    @Test
    void aSignatureFromTheFutureIsRejected() {
        String header = WebhookSignature.sign(SECRET, "{}", NOW.plusSeconds(3600));

        // An "older than" check alone would accept this. Clock skew in one
        // direction is still an attack, so drift is compared absolutely.
        assertThat(verify(SECRET, header, "{}"))
                .isEqualTo(WebhookSignature.VerificationResult.STALE);
    }

    @Test
    void aSignatureFromThePastIsRejected() {
        String header = WebhookSignature.sign(SECRET, "{}", NOW.minusSeconds(3600));

        // This is the replay case: a captured delivery replayed an hour later.
        assertThat(verify(SECRET, header, "{}"))
                .isEqualTo(WebhookSignature.VerificationResult.STALE);
    }

    @Test
    void replayingWithinTheWindowStillVerifies() {
        // Honest about a real limitation: a replay inside the tolerance window
        // cannot be detected by timestamp alone. That is why receivers must also
        // dedupe on the delivery id, and why the window is kept small.
        String header = WebhookSignature.sign(SECRET, "{}", NOW.minusSeconds(60));

        assertThat(verify(SECRET, header, "{}"))
                .isEqualTo(WebhookSignature.VerificationResult.VALID);
    }

    @Test
    void aMissingHeaderIsReportedDistinctly() {
        assertThat(verify(SECRET, null, "{}"))
                .isEqualTo(WebhookSignature.VerificationResult.MISSING_SIGNATURE);
        assertThat(verify(SECRET, "   ", "{}"))
                .isEqualTo(WebhookSignature.VerificationResult.MISSING_SIGNATURE);
    }

    @Test
    void aMalformedHeaderIsReportedDistinctly() {
        assertThat(verify(SECRET, "garbage", "{}"))
                .isEqualTo(WebhookSignature.VerificationResult.MALFORMED);
        assertThat(verify(SECRET, "t=notanumber,v1=abc", "{}"))
                .isEqualTo(WebhookSignature.VerificationResult.MALFORMED);
        assertThat(verify(SECRET, "t=1712345678", "{}"))
                .isEqualTo(WebhookSignature.VerificationResult.MALFORMED);
    }

    @Test
    void theVersionIsCarriedSoTheSchemeCanChange() {
        String v1 = WebhookSignature.sign(SECRET, "{}", NOW, "v1");
        String v2 = WebhookSignature.sign(SECRET, "{}", NOW, "v2");

        // Different versions must produce different signatures over the same body,
        // otherwise "version" would be a label rather than a rule.
        assertThat(v1).isNotEqualTo(v2);
    }

    @Test
    void theSignatureBindsTheTimestampNotJustTheBody() {
        String first = WebhookSignature.sign(SECRET, "{}", NOW);
        String second = WebhookSignature.sign(SECRET, "{}", NOW.plusSeconds(1));

        // Same body, different time, different signature. This is what makes a
        // captured signature expire instead of being replayable forever.
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void signingIsDeterministicForTheSameInputs() {
        assertThat(WebhookSignature.sign(SECRET, "payload", NOW))
                .isEqualTo(WebhookSignature.sign(SECRET, "payload", NOW));
    }

    @Test
    void aNullBodyIsSignedAsEmptyRatherThanThrowing() {
        String header = WebhookSignature.sign(SECRET, null, NOW);

        assertThat(verify(SECRET, header, null))
                .isEqualTo(WebhookSignature.VerificationResult.VALID);
    }

    @Test
    void comparisonIsConstantTimeAndNullSafe() {
        assertThat(WebhookSignature.constantTimeEquals("abc", "abc")).isTrue();
        assertThat(WebhookSignature.constantTimeEquals("abc", "abd")).isFalse();
        assertThat(WebhookSignature.constantTimeEquals("abc", "abcd")).isFalse();
        assertThat(WebhookSignature.constantTimeEquals(null, "abc")).isFalse();
        assertThat(WebhookSignature.constantTimeEquals("abc", null)).isFalse();
    }

    @Test
    void verificationResultsAreDistinctlyDescribed() {
        // A receiver needs to log "your clock is wrong" differently from "this was
        // forged"; collapsing them into a boolean makes triage harder.
        assertThat(WebhookSignature.VerificationResult.STALE.description()).contains("window");
        assertThat(WebhookSignature.VerificationResult.INVALID_SIGNATURE.description())
                .contains("does not match");
        assertThat(WebhookSignature.VerificationResult.VALID.isValid()).isTrue();
        assertThat(WebhookSignature.VerificationResult.STALE.isValid()).isFalse();
    }
}
