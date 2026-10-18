package com.pesaguard.backend.analytics.algorithm.reconciliation;

/**
 * Hungarian algorithm (Kuhn-Munkres) for optimal one-to-one assignment.
 *
 * <p>Given a cost matrix of candidate ledger/provider pairs, this finds the
 * assignment of every ledger record to a distinct provider record that minimises the
 * total cost. It is the tool for the ambiguous cases where several ledger entries
 * could each plausibly match several provider entries and a greedy pick would
 * produce a locally-reasonable but globally-worse set of matches. Minimising the
 * global cost is what prevents two records from being matched in a way that
 * "steals" a provider entry a different record needed more.
 *
 * <p>This implementation is the O(n^3) Jonker-Volgenant-style shortest-augmenting-
 * path variant with potential functions. It works on a rectangular matrix by padding
 * to square with a large cost on the padding, and it uses a big-M sentinel rather
 * than infinity so the arithmetic stays finite. Because the input may be
 * rectangular, the assignment is returned as a per-row column index, where -1 marks a
 * row (ledger record) left unassigned.
 *
 * <p>The cost matrix is built by the caller from domain costs (e.g. an amount gap
 * plus a reference-distance penalty), so the "quality" of a match is a business
 * decision expressed in the cost, not something this class invents.
 */
public final class HungarianAssignment {

    private HungarianAssignment() {
    }

    /** Cost used for padding a rectangular matrix to square. */
    private static final double BIG_M = 1e12;

    /**
     * Solve the assignment problem on a cost matrix.
     *
     * @param cost rectangular matrix {@code cost[row][col]}; all rows must be the
     *             same length. Lower cost means a more desirable pairing.
     * @return an array of length {@code rows}; element {@code r} is the column
     *         assigned to row {@code r}, or -1 if the row is left unassigned.
     */
    public static int[] solve(double[][] cost) {
        if (cost == null || cost.length == 0) {
            return new int[0];
        }
        int rows = cost.length;
        int cols = cost[0].length;
        int n = Math.max(rows, cols);
        // Pad to a square matrix. Padding cells carry BIG_M so they are only ever
        // chosen when there is genuinely no real pairing left to make.
        double[][] a = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i < rows && j < cols) {
                    a[i][j] = cost[i][j];
                } else {
                    a[i][j] = BIG_M;
                }
            }
        }
        int[] assignmentForSquare = hungarianSquare(a);

        // Map the square assignment back to the original rectangle, dropping padding.
        int[] result = new int[rows];
        java.util.Arrays.fill(result, -1);
        for (int i = 0; i < rows; i++) {
            int col = assignmentForSquare[i];
            if (col >= 0 && col < cols && cost[i][col] < BIG_M / 2.0) {
                result[i] = col;
            }
        }
        return result;
    }

    /**
     * The core O(n^3) solver on a square cost matrix.
     *
     * <p>Maintains vertex potentials {@code u} (rows) and {@code v} (columns) that
     * keep every reduced cost non-negative, and grows an alternating-tree matching
     * one augmenting path at a time. Using potentials avoids recomputing shortest
     * paths from scratch on each augmentation, which is what makes the whole thing
     * cubic rather than worse.
     */
    private static int[] hungarianSquare(double[][] a) {
        int n = a.length;
        double[] u = new double[n + 1];
        double[] v = new double[n + 1];
        int[] p = new int[n + 1];
        int[] way = new int[n + 1];
        // 1-based indexing internally so the sentinel column 0 acts as a free slot.
        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            double[] minv = new double[n + 1];
            boolean[] used = new boolean[n + 1];
            java.util.Arrays.fill(minv, Double.POSITIVE_INFINITY);
            do {
                used[j0] = true;
                int i0 = p[j0];
                double delta = Double.POSITIVE_INFINITY;
                int j1 = -1;
                for (int j = 1; j <= n; j++) {
                    if (used[j]) {
                        continue;
                    }
                    double cur = a[i0 - 1][j - 1] - u[i0] - v[j];
                    if (cur < minv[j]) {
                        minv[j] = cur;
                        way[j] = j0;
                    }
                    if (minv[j] < delta) {
                        delta = minv[j];
                        j1 = j;
                    }
                }
                for (int j = 0; j <= n; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);
            // Augment along the alternating path back to the root.
            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }

        int[] result = new int[n];
        java.util.Arrays.fill(result, -1);
        for (int j = 1; j <= n; j++) {
            if (p[j] > 0) {
                result[p[j] - 1] = j - 1;
            }
        }
        return result;
    }
}
