package com.pesaguard.backend.analytics.algorithm.anomaly;

/**
 * Local Outlier Factor (LOF) for density-based anomaly detection.
 *
 * <p>LOF compares the local density around a point to the local density around its
 * nearest neighbours. A point is anomalous when its neighbours are far denser than
 * it is - that is, the point sits on the fringe of a cluster or in an empty gap
 * between clusters. Crucially this is a *relative* measure: a point can be far from
 * everything in absolute terms and still not be an outlier if its own neighbours are
 * equally sparse, which is the behaviour that distinguishes LOF from a global
 * distance threshold.
 *
 * <p>The implementation is the reference {@code k}-nearest-neighbours form with the
 * reachability distance definition, which keeps the score stable even in regions of
 * varying density. Cost is O(n^2) in the number of points, so it is intended for
 * batch review of a tenant's recent window, not for the per-event hot path.
 */
public final class LocalOutlierFactor {

    /** Value returned when the score cannot be computed (too few points). */
    public static final double UNDEFINED = 0.0;

    private final double[][] data;
    private final int k;
    private final double[][] distances;

    /**
     * @param data feature matrix; each row is a point of equal dimensionality
     * @param k    number of neighbours; the local-density window size
     */
    public LocalOutlierFactor(double[][] data, int k) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data must not be empty");
        }
        if (k < 1 || k > data.length - 1) {
            throw new IllegalArgumentException("k must be between 1 and n-1");
        }
        this.data = data;
        this.k = k;
        this.distances = pairwiseDistances(data);
    }

    /**
     * LOF score for the point at {@code index}.
     *
     * <p>A score around 1 means the point has the same density as its neighbours
     * (not an outlier). Scores significantly greater than 1 mark outliers; the
     * larger the value, the more isolated the point relative to its neighbourhood.
     */
    public double score(int index) {
        if (data.length <= k) {
            return UNDEFINED;
        }
        int[] neighbours = kNearest(index);
        double lrd = localReachabilityDensity(index, neighbours);
        double sumRatio = 0.0;
        for (int neighbour : neighbours) {
            int[] neighbourNeighbours = kNearest(neighbour);
            double neighbourLrd = localReachabilityDensity(neighbour, neighbourNeighbours);
            if (neighbourLrd == 0.0) {
                continue;
            }
            sumRatio += neighbourLrd / lrd;
        }
        return sumRatio / neighbours.length;
    }

    /**
     * Local reachability density of a point: the inverse of the average
     * reachability distance from its k nearest neighbours. The reachability
     * distance smooths out density by using the larger of the direct distance and
     * the neighbour's own k-distance, so a point is not credited with an
     * artificially small density just because one neighbour happens to be very
     * close to it.
     */
    private double localReachabilityDensity(int index, int[] neighbours) {
        double sumReachability = 0.0;
        for (int neighbour : neighbours) {
            sumReachability += reachabilityDistance(index, neighbour);
        }
        if (sumReachability == 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        return neighbours.length / sumReachability;
    }

    private double reachabilityDistance(int a, int b) {
        return Math.max(distances[a][b], kDistance(b));
    }

    /** Distance from a point to its k-th nearest neighbour. */
    private double kDistance(int index) {
        int[] neighbours = kNearest(index);
        return distances[index][neighbours[neighbours.length - 1]];
    }

    /** Indices of the k nearest neighbours of {@code index}, nearest first. */
    private int[] kNearest(int index) {
        int n = data.length;
        // Order indices by distance from the query point, then skip the point itself
        // (distance 0) which is always the nearest of all.
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(distances[index][a], distances[index][b]));
        int[] neighbours = new int[k];
        int filled = 0;
        for (int candidate : order) {
            if (candidate == index) {
                continue;
            }
            neighbours[filled++] = candidate;
            if (filled == k) {
                break;
            }
        }
        return neighbours;
    }

    /** Euclidean distance matrix; O(n^2) space, computed once at construction. */
    private double[][] pairwiseDistances(double[][] points) {
        int n = points.length;
        double[][] matrix = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double d = euclidean(points[i], points[j]);
                matrix[i][j] = d;
                matrix[j][i] = d;
            }
        }
        return matrix;
    }

    private double euclidean(double[] a, double[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }
}
