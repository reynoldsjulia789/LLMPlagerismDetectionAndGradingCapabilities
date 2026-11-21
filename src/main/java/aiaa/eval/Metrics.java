package aiaa.eval;

import aiaa.eval.Observation.Truth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Confusion counts, rates and their uncertainty at one threshold. */
public final class Metrics {

    private Metrics() {
    }

    /**
     * Counts at a threshold. Positives are COPY, negatives are NEGATIVE-control comparisons; UNRESOLVED
     * observations never enter TP/FP/TN/FN and are only reported as a flag rate (review workload).
     * Failed comparisons count as not flagged.
     */
    public record Counts(double threshold, int tp, int fp, int tn, int fn, int unresolvedFlagged, int unresolved,
                         int failed) {

        public int positives() {
            return tp + fn;
        }

        public int negatives() {
            return fp + tn;
        }

        public double precision() {
            return tp + fp == 0 ? Double.NaN : (double) tp / (tp + fp);
        }

        public double recall() {
            return positives() == 0 ? Double.NaN : (double) tp / positives();
        }

        public double f1() {
            double p = precision();
            double r = recall();
            return Double.isNaN(p) || Double.isNaN(r) || p + r == 0 ? Double.NaN : 2 * p * r / (p + r);
        }

        public double fpr() {
            return negatives() == 0 ? Double.NaN : (double) fp / negatives();
        }

        public double fnr() {
            double r = recall();
            return Double.isNaN(r) ? Double.NaN : 1 - r;
        }

        public double unresolvedFlagRate() {
            return unresolved == 0 ? Double.NaN : (double) unresolvedFlagged / unresolved;
        }
    }

    public static Counts count(List<Observation> obs, double threshold) {
        int tp = 0;
        int fp = 0;
        int tn = 0;
        int fn = 0;
        int uf = 0;
        int u = 0;
        int failed = 0;
        for (Observation o : obs) {
            boolean flag = o.flagged(threshold);
            if (o.score() == null) {
                failed++;
            }
            switch (o.truth()) {
                case POSITIVE -> {
                    if (flag) {
                        tp++;
                    } else {
                        fn++;
                    }
                }
                case NEGATIVE -> {
                    if (flag) {
                        fp++;
                    } else {
                        tn++;
                    }
                }
                case UNRESOLVED -> {
                    u++;
                    if (flag) {
                        uf++;
                    }
                }
            }
        }
        return new Counts(threshold, tp, fp, tn, fn, uf, u, failed);
    }

    /** 95% Wilson score interval for k successes out of n; {NaN, NaN} when n = 0. */
    public static double[] wilson(int k, int n) {
        if (n == 0) {
            return new double[]{Double.NaN, Double.NaN};
        }
        double z = 1.959963984540054;
        double p = (double) k / n;
        double denom = 1 + z * z / n;
        double centre = (p + z * z / (2.0 * n)) / denom;
        double half = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / denom;
        return new double[]{Math.max(0, centre - half), Math.min(1, centre + half)};
    }

    /**
     * Cluster bootstrap: resample whole clusters (families for positives, controls for negatives) with
     * replacement, separately per class, and recompute recall and FPR. Returns
     * {recallLow, recallHigh, fprLow, fprHigh} as 95% percentile intervals.
     */
    public static double[] clusterBootstrap(List<Observation> obs, double threshold, int resamples, long seed) {
        Map<String, int[]> pos = clusters(obs, Truth.POSITIVE, threshold);
        Map<String, int[]> neg = clusters(obs, Truth.NEGATIVE, threshold);
        Random rnd = new Random(seed);
        double[] recalls = new double[resamples];
        double[] fprs = new double[resamples];
        List<int[]> p = new ArrayList<>(pos.values());
        List<int[]> n = new ArrayList<>(neg.values());
        for (int b = 0; b < resamples; b++) {
            recalls[b] = rate(p, rnd);
            fprs[b] = rate(n, rnd);
        }
        return new double[]{percentile(recalls, 0.025), percentile(recalls, 0.975),
                percentile(fprs, 0.025), percentile(fprs, 0.975)};
    }

    /** cluster -> {flagged, total} */
    private static Map<String, int[]> clusters(List<Observation> obs, Truth truth, double threshold) {
        Map<String, int[]> out = new LinkedHashMap<>();
        for (Observation o : obs) {
            if (o.truth() == truth) {
                int[] c = out.computeIfAbsent(o.cluster(), k -> new int[2]);
                if (o.flagged(threshold)) {
                    c[0]++;
                }
                c[1]++;
            }
        }
        return out;
    }

    private static double rate(List<int[]> clusters, Random rnd) {
        if (clusters.isEmpty()) {
            return Double.NaN;
        }
        long flagged = 0;
        long total = 0;
        for (int i = 0; i < clusters.size(); i++) {
            int[] c = clusters.get(rnd.nextInt(clusters.size()));
            flagged += c[0];
            total += c[1];
        }
        return total == 0 ? Double.NaN : (double) flagged / total;
    }

    private static double percentile(double[] values, double q) {
        double[] v = Arrays.stream(values).filter(x -> !Double.isNaN(x)).sorted().toArray();
        if (v.length == 0) {
            return Double.NaN;
        }
        return v[(int) Math.min(v.length - 1, Math.max(0, Math.round(q * (v.length - 1))))];
    }

    public static long clusterCount(List<Observation> obs, Truth truth) {
        return obs.stream().filter(o -> o.truth() == truth).map(Observation::cluster).distinct().count();
    }
}
