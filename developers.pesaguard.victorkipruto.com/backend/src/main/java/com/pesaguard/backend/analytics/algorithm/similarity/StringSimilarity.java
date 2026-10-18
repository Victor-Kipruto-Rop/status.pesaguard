package com.pesaguard.backend.analytics.algorithm.similarity;

/**
 * String-similarity metrics for fuzzy reconciliation of account references.
 *
 * <p>Two implementations are provided. Levenshtein is the general edit-distance
 * metric and is the safer default for references where digits are swapped or
 * inserted. Jaro-Winkler weights a common prefix more heavily and is better at
 * human-typed references where a typo tends to occur near the start of the token
 * (a misspelt surname, a truncated account number).
 *
 * <p>Both return a normalized similarity in {@code [0, 1]} where 1 is identical,
 * so callers can hold a single tolerance threshold regardless of which metric is
 * configured.
 */
public final class StringSimilarity {

    private StringSimilarity() {
    }

    /**
     * Levenshtein similarity in {@code [0, 1]}: {@code 1 - distance / maxLen}.
     *
     * <p>Case-insensitive. Normalising case here avoids a caller having to remember
     * to do it and prevents a case-only difference from looking like a mismatch.
     */
    public static double levenshtein(String a, String b) {
        if (a == null || b == null) {
            return 0.0;
        }
        String x = a.toLowerCase();
        String y = b.toLowerCase();
        if (x.equals(y)) {
            return 1.0;
        }
        if (x.isEmpty()) {
            return 0.0;
        }
        if (y.isEmpty()) {
            return 0.0;
        }
        int distance = levenshteinDistance(x, y);
        int maxLen = Math.max(x.length(), y.length());
        return 1.0 - (double) distance / maxLen;
    }

    /**
     * Standard two-row Levenshtein edit distance.
     *
     * <p>The rolling-row form keeps memory to {@code O(min(len))} regardless of
     * input size, which matters because reconciliation runs this against every
     * candidate pair on a batch.
     */
    static int levenshteinDistance(String a, String b) {
        // Keep the shorter string on the inner loop to minimise the row buffer.
        if (a.length() < b.length()) {
            String tmp = a;
            a = b;
            b = tmp;
        }
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= b.length(); j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /**
     * Jaro-Winkler similarity in {@code [0, 1]}.
     *
     * <p>Case-insensitive. The prefix boost is capped at four characters and a
     * scaling factor of 0.1, the standard configuration, so the boost can never
     * dominate the underlying Jaro score.
     */
    public static double jaroWinkler(String a, String b) {
        if (a == null || b == null) {
            return 0.0;
        }
        String x = a.toLowerCase();
        String y = b.toLowerCase();
        if (x.equals(y)) {
            return 1.0;
        }
        if (x.isEmpty() || y.isEmpty()) {
            return 0.0;
        }
        double jaro = jaro(x, y);
        int prefix = commonPrefixLength(x, y, 4);
        return jaro + prefix * 0.1 * (1.0 - jaro);
    }

    /** Jaro similarity: ratio of matched characters to transpositions. */
    static double jaro(String a, String b) {
        int matchWindow = Math.max(a.length(), b.length()) / 2 - 1;
        if (matchWindow < 0) {
            matchWindow = 0;
        }
        boolean[] aMatched = new boolean[a.length()];
        boolean[] bMatched = new boolean[b.length()];
        int matches = 0;
        for (int i = 0; i < a.length(); i++) {
            int start = Math.max(0, i - matchWindow);
            int end = Math.min(i + matchWindow + 1, b.length());
            for (int j = start; j < end; j++) {
                if (bMatched[j] || a.charAt(i) != b.charAt(j)) {
                    continue;
                }
                aMatched[i] = true;
                bMatched[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) {
            return 0.0;
        }
        // Count transpositions: matched characters appearing in different order.
        double transpositions = 0;
        int k = 0;
        for (int i = 0; i < a.length(); i++) {
            if (!aMatched[i]) {
                continue;
            }
            while (!bMatched[k]) {
                k++;
            }
            if (a.charAt(i) != b.charAt(k)) {
                transpositions++;
            }
            k++;
        }
        transpositions /= 2.0;
        double m = matches;
        return (m / a.length() + m / b.length() + (m - transpositions) / m) / 3.0;
    }

    private static int commonPrefixLength(String a, String b, int max) {
        int n = Math.min(Math.min(a.length(), b.length()), max);
        int prefix = 0;
        for (int i = 0; i < n; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                break;
            }
            prefix++;
        }
        return prefix;
    }
}
