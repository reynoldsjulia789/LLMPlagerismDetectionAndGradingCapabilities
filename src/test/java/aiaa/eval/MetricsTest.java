package aiaa.eval;

import aiaa.eval.Metrics.Counts;
import aiaa.eval.Observation.Truth;
import aiaa.eval.ThresholdSelector.Selection;
import aiaa.eval.ThresholdSelector.Status;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsTest {

    private static final ConfigKey KEY = new ConfigKey("Hw1", "STRIP", "TOKEN", "tok", "", "PEER", "PAIR");

    private static Observation obs(Truth t, Double score, String cluster) {
        return new Observation("id", "a", "b", "VALIDATION", t, score, cluster, null, null, null, null, null, "OK");
    }

    @Test
    void countsSeparateUnresolvedAndFailures() {
        List<Observation> o = List.of(obs(Truth.POSITIVE, 90.0, "f1"), obs(Truth.POSITIVE, 40.0, "f2"),
                obs(Truth.POSITIVE, null, "f3"), obs(Truth.NEGATIVE, 60.0, "n1"), obs(Truth.NEGATIVE, 10.0, "n1"),
                obs(Truth.UNRESOLVED, 99.0, "u"));
        Counts c = Metrics.count(o, 50);
        assertEquals(1, c.tp());
        assertEquals(2, c.fn(), "a failed comparison is a missed detection");
        assertEquals(1, c.fp());
        assertEquals(1, c.tn());
        assertEquals(1, c.unresolvedFlagged());
        assertEquals(1, c.failed());
        assertEquals(1.0 / 3, c.recall(), 1e-12);
        assertEquals(0.5, c.fpr(), 1e-12);
        assertEquals(0.5, c.precision(), 1e-12);
        assertTrue(Metrics.count(o, 95).tp() == 0 && Double.isNaN(Metrics.count(o, 95).precision()));
    }

    @Test
    void wilsonMatchesKnownValues() {
        double[] ci = Metrics.wilson(0, 10);
        assertEquals(0.0, ci[0], 1e-9);
        assertEquals(0.2775, ci[1], 1e-3);
        double[] half = Metrics.wilson(50, 100);
        assertEquals(0.4038, half[0], 1e-3);
        assertEquals(0.5962, half[1], 1e-3);
        assertTrue(Double.isNaN(Metrics.wilson(0, 0)[0]));
    }

    @Test
    void selectionMaximizesRecallUnderFprCapAndPrefersHigherThreshold() {
        List<Observation> val = new ArrayList<>();
        for (double s : new double[]{60, 70, 80, 90}) {
            val.add(obs(Truth.POSITIVE, s, "p" + s));
        }
        for (int i = 0; i < 39; i++) {
            val.add(obs(Truth.NEGATIVE, 40.0, "n"));
        }
        val.add(obs(Truth.NEGATIVE, 65.0, "n")); // 1 of 40 negatives = 2.5% FPR is allowed
        Selection s = ThresholdSelector.select(KEY, val, 0.05);
        assertEquals(Status.OK, s.status());
        assertEquals(60.0, s.threshold(), 1e-9, "highest threshold that keeps recall 1.0");
        assertEquals(1.0, s.validation().recall(), 1e-12);
        assertTrue(s.sweep().stream().anyMatch(c -> c.threshold() == 60.5), "fine local search ran");

        Selection strict = ThresholdSelector.select(KEY, val, 0.0);
        assertEquals(70.0, strict.threshold(), 1e-9, "above the 65 negative; ties (same recall, FPR) go to the higher threshold");
        assertEquals(0.75, strict.validation().recall(), 1e-12);
    }

    @Test
    void reportsWhenNoThresholdIsUsable() {
        List<Observation> noNeg = List.of(obs(Truth.POSITIVE, 80.0, "p"));
        assertEquals(Status.NO_NEGATIVES, ThresholdSelector.select(KEY, noNeg, 0.05).status());
        List<Observation> hopeless = List.of(obs(Truth.POSITIVE, 80.0, "p"), obs(Truth.NEGATIVE, 100.0, "n"));
        Selection s = ThresholdSelector.select(KEY, hopeless, 0.05);
        assertEquals(Status.NO_THRESHOLD_MEETS_FPR, s.status());
        assertNull(s.threshold());
        List<Observation> useless = List.of(obs(Truth.POSITIVE, 10.0, "p"), obs(Truth.NEGATIVE, 99.0, "n"));
        assertEquals(Status.NO_USEFUL_THRESHOLD, ThresholdSelector.select(KEY, useless, 0.05).status());
    }

    @Test
    void clusterBootstrapResamplesWholeClusters() {
        List<Observation> o = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            o.add(obs(Truth.POSITIVE, 90.0, "fam-a")); // one cluster, all detected
            o.add(obs(Truth.POSITIVE, 10.0, "fam-b")); // one cluster, all missed
        }
        o.add(obs(Truth.NEGATIVE, 10.0, "ctrl"));
        double[] b = Metrics.clusterBootstrap(o, 50, 500, 1);
        assertEquals(0.0, b[0], 1e-12, "a resample can draw fam-b twice");
        assertEquals(1.0, b[1], 1e-12, "or fam-a twice");
        assertEquals(0.0, b[2], 1e-12);
        assertEquals(2, Metrics.clusterCount(o, Truth.POSITIVE));
    }
}
