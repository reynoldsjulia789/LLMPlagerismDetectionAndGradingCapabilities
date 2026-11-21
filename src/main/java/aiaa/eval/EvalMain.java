package aiaa.eval;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Command-line entry point for threshold selection and held-out detection metrics. */
public final class EvalMain {

    private static final String USAGE = """
            Detection thresholds and metrics (spec 4.3)

            Usage:
              EvalMain select --run <pdm-output dir>... [options]     sweep + select on VALIDATION, then freeze
              EvalMain test   --run <pdm-output dir>... [options]     apply frozen thresholds once to TEST

            Options:
              --run <dir>                a PDM output folder (repeatable: e.g. one per homework and starter mode).
                                         Repeated runs of the same configuration must be evaluated separately
              --labels <dir>             labels folder from LabelMain, default synthetic-data/labels
              --out <dir>                default eval-output
              --max-fpr <f>              validation FPR cap of the selection rule, default 0.05
              --bootstrap <n>            cluster-bootstrap resamples for test intervals, default 2000
              --seed <n>                 bootstrap seed, default 42
              --overwrite                (select) replace frozen thresholds; refused once a test evaluation exists
              --allow-reevaluation       (test) evaluate the held-out set again; logged in evaluation_log.jsonl

            select writes: sweep.csv, selection_summary.csv, validation_errors.csv, thresholds.json (frozen)
            test writes:   test_results.csv, test_breakdown.csv, test_errors.csv, detection_results.csv,
                           sweep_test_postfreeze.csv, starter_mode_comparison.csv, test_evaluation.json
            """;

    private EvalMain() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    public static Object run(String[] args) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            System.out.println(USAGE);
            return null;
        }
        String phase = args[0];
        List<Path> runs = new ArrayList<>();
        Path labels = Path.of("synthetic-data", "labels");
        Path out = Path.of("eval-output");
        double maxFpr = 0.05;
        int bootstrap = 2000;
        long seed = 42;
        boolean overwrite = false;
        boolean reeval = false;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--run" -> runs.add(Path.of(value(args, ++i, a)));
                case "--labels" -> labels = Path.of(value(args, ++i, a));
                case "--out" -> out = Path.of(value(args, ++i, a));
                case "--max-fpr" -> maxFpr = Double.parseDouble(value(args, ++i, a));
                case "--bootstrap" -> bootstrap = Integer.parseInt(value(args, ++i, a));
                case "--seed" -> seed = Long.parseLong(value(args, ++i, a));
                case "--overwrite" -> overwrite = true;
                case "--allow-reevaluation" -> reeval = true;
                default -> throw new IllegalArgumentException("Unknown option " + a);
            }
        }
        if (runs.isEmpty()) {
            throw new IllegalArgumentException("give at least one --run <pdm-output dir>");
        }
        Evaluator ev = new Evaluator(labels, runs, out, maxFpr, bootstrap, seed);
        switch (phase) {
            case "select" -> {
                List<ThresholdSelector.Selection> sel = ev.select(overwrite);
                System.out.println("Selected and froze thresholds for " + sel.size() + " configurations:");
                for (ThresholdSelector.Selection s : sel) {
                    System.out.printf(Locale.ROOT, "  %-70s %-22s %s%n", s.key().id(), s.status(),
                            s.threshold() == null ? "-" : String.format(Locale.ROOT, "t=%.1f recall=%.3f fpr=%.3f",
                                    s.threshold(), s.validation().recall(), s.validation().fpr()));
                }
                System.out.println("Wrote " + out.toAbsolutePath());
                return sel;
            }
            case "test" -> {
                List<Evaluator.TestResult> res = ev.test(reeval);
                System.out.println("Held-out TEST results at frozen thresholds:");
                for (Evaluator.TestResult t : res) {
                    System.out.printf(Locale.ROOT, "  %-70s t=%.1f  recall=%s fpr=%s  (pos %d, neg %d)%n",
                            t.key().id(), t.threshold(), Evaluator.fmt(t.counts().recall(), 3),
                            Evaluator.fmt(t.counts().fpr(), 3), t.counts().positives(), t.counts().negatives());
                }
                System.out.println("Wrote " + out.toAbsolutePath());
                return res;
            }
            default -> throw new IllegalArgumentException("first argument must be select or test");
        }
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[i];
    }
}
