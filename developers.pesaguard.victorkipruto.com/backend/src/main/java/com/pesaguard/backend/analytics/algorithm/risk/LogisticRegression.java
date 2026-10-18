package com.pesaguard.backend.analytics.algorithm.risk;

import java.util.Arrays;

/**
 * Logistic regression classifier for risk scoring, trained by gradient descent.
 *
 * <p>This is the supervised counterpart to the rule engine: where the rule engine
 * encodes human judgement explicitly, logistic regression learns the weights from
 * labelled historical outcomes (e.g. transactions later confirmed fraudulent). It
 * exists because the rule engine's hand-set weights cannot be tuned to a specific
 * portfolio without labelled data - and once that data exists, the learned model
 * usually out-predicts hand-tuned rules.
 *
 * <p>Features are standardised before training so one large-magnitude feature cannot
 * dominate the gradient, and so the fitted model scores consistently at inference
 * time. The output is a calibrated probability in {@code [0, 1]}: the estimated
 * likelihood a transaction belongs to the positive (high-risk) class.
 *
 * <p>The model is a probability, not a decision. Thresholding it into a
 * hold/release action is a policy choice owned by the service layer, which must weigh
 * false-positive cost, not just accuracy.
 */
public final class LogisticRegression {

    private final double[] weights;
    private final double[] featureMeans;
    private final double[] featureStdDevs;
    private double bias;
    private boolean fitted;

    /** Construct a model for {@code featureCount} features, not yet trained. */
    public LogisticRegression(int featureCount) {
        if (featureCount < 1) {
            throw new IllegalArgumentException("featureCount must be at least 1");
        }
        this.weights = new double[featureCount];
        this.featureMeans = new double[featureCount];
        this.featureStdDevs = new double[featureCount];
        this.fitted = false;
    }

    /**
     * Fit the model on a labelled dataset.
     *
     * @param features      each row is a training example of {@code featureCount} values
     * @param labels        binary labels in {@code {0, 1}} aligned with {@code features}
     * @param learningRate  gradient-descent step size
     * @param epochs        number of full passes over the dataset
     * @param l2Regularisation ridge penalty to discourage overfitting; 0 disables it
     */
    public void fit(double[][] features, double[] labels, double learningRate,
                    int epochs, double l2Regularisation) {
        if (features.length != labels.length) {
            throw new IllegalArgumentException("features and labels must have the same length");
        }
        if (features.length == 0) {
            throw new IllegalArgumentException("cannot fit on an empty dataset");
        }
        computeFeatureStatistics(features);
        Arrays.fill(weights, 0.0);
        bias = 0.0;

        for (int epoch = 0; epoch < epochs; epoch++) {
            double[] gradient = new double[weights.length];
            double biasGradient = 0.0;
            for (int i = 0; i < features.length; i++) {
                double[] standardised = standardise(features[i]);
                double prediction = sigmoid(dot(weights, standardised) + bias);
                double error = prediction - labels[i];
                for (int j = 0; j < weights.length; j++) {
                    gradient[j] += error * standardised[j];
                }
                biasGradient += error;
            }
            double invN = 1.0 / features.length;
            for (int j = 0; j < weights.length; j++) {
                double l2 = l2Regularisation * weights[j];
                weights[j] -= learningRate * (gradient[j] * invN + l2);
            }
            bias -= learningRate * biasGradient * invN;
        }
        fitted = true;
    }

    /**
     * Predict the probability of the positive class for one example.
     *
     * <p>Applies the same feature standardisation learned during training, so the
     * caller must supply raw (un-standardised) features in the original units.
     */
    public double predictProbability(double[] features) {
        if (!fitted) {
            throw new IllegalStateException("model must be fit before prediction");
        }
        double[] standardised = standardise(features);
        return sigmoid(dot(weights, standardised) + bias);
    }

    private void computeFeatureStatistics(double[][] features) {
        for (int j = 0; j < featureMeans.length; j++) {
            double sum = 0.0;
            for (double[] row : features) {
                sum += row[j];
            }
            featureMeans[j] = sum / features.length;
        }
        for (int j = 0; j < featureStdDevs.length; j++) {
            double sumSq = 0.0;
            for (double[] row : features) {
                double diff = row[j] - featureMeans[j];
                sumSq += diff * diff;
            }
            featureStdDevs[j] = Math.sqrt(sumSq / features.length);
        }
    }

    /** Standardise one feature vector; a zero-variance feature maps to zero. */
    private double[] standardise(double[] features) {
        double[] result = new double[features.length];
        for (int j = 0; j < features.length; j++) {
            result[j] = featureStdDevs[j] == 0.0
                    ? 0.0
                    : (features[j] - featureMeans[j]) / featureStdDevs[j];
        }
        return result;
    }

    private static double dot(double[] a, double[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    /**
     * Numerically stable logistic sigmoid.
     *
     * <p>The two-branch form avoids {@code Math.exp} overflowing on large negative
     * inputs, which would otherwise return infinity and corrupt the gradient.
     */
    static double sigmoid(double z) {
        if (z >= 0.0) {
            return 1.0 / (1.0 + Math.exp(-z));
        }
        double expZ = Math.exp(z);
        return expZ / (1.0 + expZ);
    }
}
