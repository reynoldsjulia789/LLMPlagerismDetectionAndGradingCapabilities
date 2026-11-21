package aiaa.eval;

import aiaa.eval.Metrics.Counts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Threshold sweep and selection (spec 4.3): coarse sweep 0..100 in steps of 5, then a finer local
 * search (step 0.5, within +-5 of the best coarse threshold). A score flags when score >= threshold.
 *
 * <p>Rule: among thresholds whose validation false-positive rate is at most {@code maxFpr}, take the
 * one with the highest recall; ties go to the lower FPR, then to the higher (more conservative) threshold.
 */
public final class ThresholdSelector {

    public enum Status {
        OK,
        /** No validation negatives, so the FPR constraint cannot be checked. */
        NO_NEGATIVES,
        /** No validation positives, so recall is undefined. */
        NO_POSITIVES,
        /** Every threshold exceeds the FPR cap. */
        NO_THRESHOLD_MEETS_FPR,
        /** The FPR cap can only be met with zero recall. */
        NO_USEFUL_THRESHOLD
    }

    public record Selection(ConfigKey key, Status status, Double threshold, Counts validation, List<Counts> sweep) {
    }

    public static final double COARSE_STEP = 5;
    public static final double FINE_STEP = 0.5;
    public static final double FINE_RADIUS = 5;

    private ThresholdSelector() {
    }

    public static List<Double> coarseGrid() {
        List<Double> out = new ArrayList<>();
        for (double t = 0; t <= 100 + 1e-9; t += COARSE_STEP) {
            out.add(t);
        }
        return out;
    }

    static List<Double> fineGrid(double around) {
        List<Double> out = new ArrayList<>();
        for (double t = Math.max(0, around - FINE_RADIUS); t <= Math.min(100, around + FINE_RADIUS) + 1e-9; t += FINE_STEP) {
            if (Math.abs(t % COARSE_STEP) > 1e-9) {
                out.add(Math.round(t * 10) / 10.0);
            }
        }
        return out;
    }

    public static Selection select(ConfigKey key, List<Observation> validation, double maxFpr) {
        List<Counts> sweep = new ArrayList<>();
        for (double t : coarseGrid()) {
            sweep.add(Metrics.count(validation, t));
        }
        Counts any = sweep.getFirst();
        if (any.negatives() == 0) {
            return new Selection(key, Status.NO_NEGATIVES, null, null, sweep);
        }
        if (any.positives() == 0) {
            return new Selection(key, Status.NO_POSITIVES, null, null, sweep);
        }
        Optional<Counts> coarseBest = best(sweep, maxFpr);
        if (coarseBest.isEmpty()) {
            return new Selection(key, Status.NO_THRESHOLD_MEETS_FPR, null, null, sweep);
        }
        for (double t : fineGrid(coarseBest.get().threshold())) {
            sweep.add(Metrics.count(validation, t));
        }
        sweep.sort(Comparator.comparingDouble(Counts::threshold));
        Counts chosen = best(sweep, maxFpr).orElseThrow();
        Status status = chosen.tp() == 0 ? Status.NO_USEFUL_THRESHOLD : Status.OK;
        return new Selection(key, status, chosen.threshold(), chosen, sweep);
    }

    static Optional<Counts> best(List<Counts> sweep, double maxFpr) {
        return sweep.stream()
                .filter(c -> c.fpr() <= maxFpr + 1e-12)
                .max(Comparator.comparingDouble(Counts::recall)
                        .thenComparing(Comparator.comparingDouble(Counts::fpr).reversed())
                        .thenComparingDouble(Counts::threshold));
    }
}
