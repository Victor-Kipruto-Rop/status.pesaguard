package com.pesaguard.backend.analytics.algorithm.anomaly;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Isolation Forest for multivariate anomaly detection.
 *
 * <p>The algorithm builds an ensemble of random binary trees that recursively
 * partition the feature space by choosing a random feature and a random split
 * point. The key insight is that anomalies are "few and different": they sit in
 * sparse regions and are therefore isolated by fewer splits, which gives them a
 * shorter average path length through the trees. An anomaly score is derived from
 * how much shorter that path is than expected for a well-populated point.
 *
 * <p>Unlike the distance/statistical detectors, this captures interactions between
 * features. A transaction whose amount is ordinary and whose hour is ordinary can
 * still be an outlier because of the unusual *combination*; Isolation Forest finds
 * that, a per-feature threshold cannot.
 *
 * <p>This is a training-based model: it is fit on a baseline window of normal
 * traffic and then scores new points against that learned geometry. The randomness
 * is seeded so a given (data, seed) pair is reproducible, which is required for
 * auditability of a fraud signal.
 */
public final class IsolationForest {

    private final int numTrees;
    private final int sampleSize;
    private final long seed;
    private final List<Node> forest;

    /** One node in a single isolation tree. */
    private static final class Node {
        int splitFeature = -1;
        double splitValue;
        Node left;
        Node right;
        int size;

        boolean isLeaf() {
            return left == null && right == null;
        }
    }

    /**
     * @param numTrees   ensemble size; more trees give a more stable score at more cost
     * @param sampleSize subsample drawn per tree; smaller samples sharpen isolation
     * @param seed       RNG seed for reproducible partitions
     */
    public IsolationForest(int numTrees, int sampleSize, long seed) {
        if (numTrees < 1) {
            throw new IllegalArgumentException("numTrees must be at least 1");
        }
        if (sampleSize < 2) {
            throw new IllegalArgumentException("sampleSize must be at least 2");
        }
        this.numTrees = numTrees;
        this.sampleSize = sampleSize;
        this.seed = seed;
        this.forest = new ArrayList<>();
    }

    /**
     * Fit the forest on a feature matrix. Each row is a data point, each column a
     * feature. The matrix must be rectangular (all rows the same length).
     */
    public void fit(double[][] data) {
        if (data.length < 2) {
            throw new IllegalArgumentException("need at least two points to fit");
        }
        forest.clear();
        Random random = new Random(seed);
        int effectiveSample = Math.min(sampleSize, data.length);
        for (int t = 0; t < numTrees; t++) {
            double[][] sample = subsample(data, effectiveSample, random);
            forest.add(buildTree(sample, 0, random));
        }
    }

    private double[][] subsample(double[][] data, int size, Random random) {
        double[][] sample = new double[size][];
        for (int i = 0; i < size; i++) {
            sample[i] = data[random.nextInt(data.length)];
        }
        return sample;
    }

    private Node buildTree(double[][] data, int depth, Random random) {
        Node node = new Node();
        node.size = data.length;
        // Height cap mirrors the expected depth of a tree built on a random sample;
        // beyond it, extra splits only overfit the sample, not the distribution.
        int maxDepth = (int) Math.ceil(Math.log(Math.max(data.length, 2)) / Math.log(2.0));
        if (depth >= maxDepth || data.length <= 1) {
            return node;
        }
        int features = data[0].length;
        // Resample until a feature with a real range is found; a constant feature
        // cannot split and would otherwise spin until the loop exhausts itself.
        int feature = -1;
        double min = 0.0;
        double max = 0.0;
        for (int attempt = 0; attempt < features; attempt++) {
            int candidate = random.nextInt(features);
            double lo = Double.POSITIVE_INFINITY;
            double hi = Double.NEGATIVE_INFINITY;
            for (double[] row : data) {
                lo = Math.min(lo, row[candidate]);
                hi = Math.max(hi, row[candidate]);
            }
            if (hi > lo) {
                feature = candidate;
                min = lo;
                max = hi;
                break;
            }
        }
        if (feature < 0) {
            // All features identical across this sample: cannot split further.
            return node;
        }
        node.splitFeature = feature;
        node.splitValue = min + random.nextDouble() * (max - min);
        List<double[]> left = new ArrayList<>();
        List<double[]> right = new ArrayList<>();
        for (double[] row : data) {
            if (row[feature] < node.splitValue) {
                left.add(row);
            } else {
                right.add(row);
            }
        }
        node.left = buildTree(left.toArray(new double[0][]), depth + 1, random);
        node.right = buildTree(right.toArray(new double[0][]), depth + 1, random);
        return node;
    }

    /**
     * Anomaly score for a single point in {@code [0, 1]}.
     *
     * <p>Scores close to 1 indicate a strong anomaly, scores below ~0.5 indicate a
     * point consistent with the bulk of the training data, and ~0.5 is the boundary
     * where the decision is genuinely ambiguous.
     */
    public double score(double[] point) {
        if (forest.isEmpty()) {
            throw new IllegalStateException("forest must be fit before scoring");
        }
        double totalPathLength = 0.0;
        for (Node tree : forest) {
            totalPathLength += pathLength(point, tree, 0);
        }
        double avgPathLength = totalPathLength / forest.size();
        // c(n): expected path length of an unsuccessful search in a BST of n points.
        double normalisation = 2.0 * (harmonic(forest.size()) - 1.0);
        return Math.pow(2.0, -avgPathLength / normalisation);
    }

    private double pathLength(double[] point, Node node, int depth) {
        if (node.isLeaf()) {
            // Unsuccessful-search correction for a leaf holding more than one point.
            return depth + c(node.size);
        }
        if (point[node.splitFeature] < node.splitValue) {
            return pathLength(point, node.left, depth + 1);
        }
        return pathLength(point, node.right, depth + 1);
    }

    /** Harmonic number approximation used by the path-length normalisation. */
    private double harmonic(int n) {
        if (n <= 0) {
            return 0.0;
        }
        return Math.log(n) + 0.5772156649;
    }

    /** Expected path length adjustment for an external (unsuccessful) search. */
    private double c(int n) {
        if (n <= 1) {
            return 0.0;
        }
        if (n == 2) {
            return 1.0;
        }
        return 2.0 * harmonic(n - 1) - 2.0 * (n - 1) / n;
    }
}
