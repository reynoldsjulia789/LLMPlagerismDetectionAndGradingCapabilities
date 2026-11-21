package aiaa.labels;

import aiaa.data.DataLayout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Command-line entry point for ground-truth labels and the dev/validation/test split. */
public final class LabelMain {

    private static final String USAGE = """
            Labels and splits (ground truth for threshold selection and held-out evaluation)

            Usage:
              LabelMain [options]

            Options:
              --data-root <dir>          dataset root in the shared layout, default synthetic-data
              --controls <dir>           negative controls root, default negative-controls
              --no-controls              label without negative controls (no true negatives!)
              --homework <Hw>            homework to label (repeatable); default: all under the root.
                                         All homeworks are split together so a student's work stays in one split
              --private-dir <dir>        private ID maps (used only to group a student's submissions),
                                         default private
              --out <dir>                default <data-root>/labels
              --seed <n>                 split seed, default 42
              --split <d,v,t>            dev,validation,test fractions, default 0.5,0.25,0.25
              --group-students <scope>   within-homework (default) or across-homeworks: how far a student's
                                         submissions are kept in one split. Across homeworks is infeasible on
                                         this data (nearly every student did every homework, which chains all
                                         submissions into two families)

            Outputs: <out>/<Hw>/pair_labels.csv, <out>/<Hw>/submission_labels.csv,
                     <out>/split_manifest.csv, <out>/split_log.json
            """;

    private LabelMain() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Run with --help for usage.");
            System.exit(2);
        }
    }

    public static Labeler.Summary run(String[] args) {
        Path dataRoot = Path.of("synthetic-data");
        Path controls = Path.of(DataLayout.DEFAULT_CONTROLS_ROOT);
        List<String> homeworks = new ArrayList<>();
        Path privateDir = Path.of("private");
        Path out = null;
        long seed = 42;
        double[] fractions = {0.5, 0.25, 0.25};
        boolean acrossHomeworks = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--help", "-h" -> {
                    System.out.println(USAGE);
                    return null;
                }
                case "--data-root" -> dataRoot = Path.of(value(args, ++i, a));
                case "--controls" -> controls = Path.of(value(args, ++i, a));
                case "--no-controls" -> controls = null;
                case "--homework" -> homeworks.add(value(args, ++i, a));
                case "--private-dir" -> privateDir = Path.of(value(args, ++i, a));
                case "--out" -> out = Path.of(value(args, ++i, a));
                case "--seed" -> seed = Long.parseLong(value(args, ++i, a));
                case "--split" -> {
                    String[] parts = value(args, ++i, a).split(",");
                    if (parts.length != 3) {
                        throw new IllegalArgumentException("--split needs three comma-separated fractions");
                    }
                    fractions = new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                            Double.parseDouble(parts[2])};
                }
                case "--group-students" -> {
                    String scope = value(args, ++i, a);
                    if (!scope.equals("across-homeworks") && !scope.equals("within-homework")) {
                        throw new IllegalArgumentException("--group-students is across-homeworks or within-homework");
                    }
                    acrossHomeworks = scope.equals("across-homeworks");
                }
                default -> throw new IllegalArgumentException("Unknown option " + a);
            }
        }
        if (controls != null && !Files.isDirectory(controls)) {
            System.err.println("Warning: no negative controls at " + controls + "; there will be no NEGATIVE labels");
            controls = null;
        }
        LabelConfig config = new LabelConfig(dataRoot, controls, homeworks, privateDir,
                out == null ? dataRoot.resolve("labels") : out, seed, fractions, acrossHomeworks);
        Labeler.Summary s = new Labeler(config).run();
        System.out.printf(Locale.ROOT, "Labeled %d submissions in %d families%n", s.submissions(), s.families());
        s.perHomework().forEach((hw, splits) -> System.out.printf(Locale.ROOT, "  %s: %s%n", hw, splits));
        System.out.println("  pair labels (within a split): " + s.pairLabels());
        System.out.println("  cross-split pairs (excluded from metrics): " + s.crossSplitPairs());
        if (s.originalsWithoutStudentKey() > 0) {
            System.out.println("  WARNING: " + s.originalsWithoutStudentKey() + " originals could not be matched to a "
                    + "student (missing private ID maps?); they are grouped alone");
        }
        System.out.println("Wrote " + config.outDir().toAbsolutePath());
        return s;
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[i];
    }
}
