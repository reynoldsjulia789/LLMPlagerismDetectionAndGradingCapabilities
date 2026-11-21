package aiaa.eval;

import aiaa.eval.Metrics.Counts;
import aiaa.eval.Observation.Truth;
import aiaa.eval.ThresholdSelector.Selection;
import aiaa.pdm.Csv;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Two-phase detection evaluation that enforces the spec's "freeze before held-out evaluation":
 * <ol>
 *   <li>{@code select}: sweep thresholds on DEV and VALIDATION, select one per configuration on
 *       VALIDATION, and freeze them in {@code thresholds.json} together with SHA-256 hashes of every input.</li>
 *   <li>{@code test}: apply the frozen thresholds once to TEST. Refuses if any input changed since the
 *       freeze, and refuses a second test evaluation unless explicitly allowed (which is logged).</li>
 * </ol>
 */
public final class Evaluator {

    static final String THRESHOLDS = "thresholds.json";
    static final String TEST_EVALUATION = "test_evaluation.json";
    static final String LOG = "evaluation_log.jsonl";

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final ObjectMapper JSONL = new ObjectMapper();

    private final Path labelsDir;
    private final List<Path> runDirs;
    private final Path outDir;
    private final double maxFpr;
    private final int bootstrapResamples;
    private final long seed;

    public Evaluator(Path labelsDir, List<Path> runDirs, Path outDir, double maxFpr, int bootstrapResamples, long seed) {
        this.labelsDir = labelsDir;
        this.runDirs = runDirs;
        this.outDir = outDir;
        this.maxFpr = maxFpr;
        this.bootstrapResamples = bootstrapResamples;
        this.seed = seed;
    }

    // ================================================================== select

    public List<Selection> select(boolean overwrite) {
        if (Files.exists(outDir.resolve(TEST_EVALUATION))) {
            throw new IllegalStateException("A test evaluation already exists in " + outDir
                    + "; re-selecting thresholds after seeing test results is not allowed. Use a new --out.");
        }
        if (Files.exists(outDir.resolve(THRESHOLDS)) && !overwrite) {
            throw new IllegalStateException("Thresholds are already frozen in " + outDir.resolve(THRESHOLDS)
                    + " (pass --overwrite to re-select before any test evaluation)");
        }
        EvalData.Loaded data = EvalData.load(labelsDir, runDirs);
        data.warnings().forEach(w -> System.err.println("Warning: " + w));
        List<Selection> selections = new ArrayList<>();
        List<List<Object>> sweepRows = new ArrayList<>();
        List<List<Object>> errorRows = new ArrayList<>();
        for (Map.Entry<ConfigKey, List<Observation>> e : data.observations().entrySet()) {
            ConfigKey key = e.getKey();
            List<Observation> dev = bySplit(e.getValue(), "DEV");
            List<Observation> val = bySplit(e.getValue(), "VALIDATION");
            Selection s = ThresholdSelector.select(key, val, maxFpr);
            selections.add(s);
            for (double t : ThresholdSelector.coarseGrid()) {
                sweepRows.add(sweepRow(key, "DEV", Metrics.count(dev, t)));
            }
            for (Counts c : s.sweep()) {
                sweepRows.add(sweepRow(key, "VALIDATION", c));
            }
            if (s.threshold() != null) {
                errorRows.addAll(errorRows(key, "VALIDATION", val, s.threshold(), data));
            }
        }
        Csv.write(outDir.resolve("sweep.csv"), sweepHeader(), sweepRows);
        Csv.write(outDir.resolve("validation_errors.csv"), errorHeader(), errorRows);
        writeSelectionSummary(selections);
        freeze(selections, data.inputs());
        return selections;
    }

    private void freeze(List<Selection> selections, List<Path> inputs) {
        ObjectNode root = JSON.createObjectNode();
        root.put("frozen_utc", Instant.now().toString());
        root.put("rule", "maximum validation recall subject to validation FPR <= " + maxFpr
                + "; ties: lower FPR, then higher threshold. Flag = score >= threshold.");
        root.put("max_fpr", maxFpr);
        root.put("sweep", "coarse 0..100 step " + ThresholdSelector.COARSE_STEP + ", then step "
                + ThresholdSelector.FINE_STEP + " within +-" + ThresholdSelector.FINE_RADIUS + " of the best coarse threshold");
        ObjectNode hashes = root.putObject("input_sha256");
        for (Path p : inputs) {
            hashes.put(p.toString(), sha256(p));
        }
        ArrayNode entries = root.putArray("thresholds");
        for (Selection s : selections) {
            ObjectNode n = entries.addObject();
            putKey(n, s.key());
            n.put("status", s.status().name());
            if (s.threshold() == null) {
                n.putNull("threshold");
            } else {
                n.put("threshold", s.threshold());
            }
            if (s.validation() != null) {
                Counts c = s.validation();
                n.put("validation_tp", c.tp()).put("validation_fp", c.fp()).put("validation_tn", c.tn())
                        .put("validation_fn", c.fn()).put("validation_recall", c.recall()).put("validation_fpr", c.fpr());
            }
        }
        write(outDir.resolve(THRESHOLDS), root);
        log("freeze", Map.of("configurations", selections.size()));
    }

    // ================================================================== test

    public record TestResult(ConfigKey key, double threshold, Counts counts, double[] recallCi, double[] fprCi,
                             double[] precisionCi, double[] bootstrap, long positiveClusters, long negativeClusters) {
    }

    public List<TestResult> test(boolean allowReevaluation) {
        Path frozenFile = outDir.resolve(THRESHOLDS);
        if (!Files.exists(frozenFile)) {
            throw new IllegalStateException("No frozen thresholds at " + frozenFile + "; run the select phase first");
        }
        boolean reevaluation = Files.exists(outDir.resolve(TEST_EVALUATION));
        if (reevaluation && !allowReevaluation) {
            throw new IllegalStateException("The held-out test set was already evaluated with these thresholds ("
                    + outDir.resolve(TEST_EVALUATION) + "). The spec allows one application; pass "
                    + "--allow-reevaluation only for a documented reason (it is logged).");
        }
        JsonNode frozen = readJson(frozenFile);
        EvalData.Loaded data = EvalData.load(labelsDir, runDirs);
        verifyInputs(frozen.path("input_sha256"), data.inputs());

        Map<String, Double> thresholds = new LinkedHashMap<>();
        for (JsonNode n : frozen.path("thresholds")) {
            if (!n.path("threshold").isNull()) {
                thresholds.put(n.path("config").asText(), n.path("threshold").asDouble());
            }
        }
        List<TestResult> results = new ArrayList<>();
        List<List<Object>> breakdown = new ArrayList<>();
        List<List<Object>> sensitivity = new ArrayList<>();
        List<List<Object>> errors = new ArrayList<>();
        List<List<Object>> detections = new ArrayList<>();
        for (Map.Entry<ConfigKey, List<Observation>> e : data.observations().entrySet()) {
            ConfigKey key = e.getKey();
            Double t = thresholds.get(key.id());
            List<Observation> test = bySplit(e.getValue(), "TEST");
            for (double s : ThresholdSelector.coarseGrid()) {
                sensitivity.add(sweepRow(key, "TEST", Metrics.count(test, s)));
            }
            if (t == null) {
                continue;
            }
            Counts c = Metrics.count(test, t);
            results.add(new TestResult(key, t, c, Metrics.wilson(c.tp(), c.positives()),
                    Metrics.wilson(c.fp(), c.negatives()), Metrics.wilson(c.tp(), c.tp() + c.fp()),
                    Metrics.clusterBootstrap(test, t, bootstrapResamples, seed),
                    Metrics.clusterCount(test, Truth.POSITIVE), Metrics.clusterCount(test, Truth.NEGATIVE)));
            breakdown.addAll(breakdownRows(key, test, t));
            errors.addAll(errorRows(key, "TEST", test, t, data));
            for (Observation o : e.getValue()) {
                detections.add(detectionRow(key, o, t));
            }
        }
        writeTestResults(results);
        Csv.write(outDir.resolve("test_breakdown.csv"), List.of("config", "homework", "starter_mode", "method",
                "model_id", "comparison", "level", "threshold", "dimension", "value", "positives", "detected", "recall",
                "negatives", "false_positives", "fpr"), breakdown);
        Csv.write(outDir.resolve("sweep_test_postfreeze.csv"), sweepHeader(), sensitivity);
        Csv.write(outDir.resolve("test_errors.csv"), errorHeader(), errors);
        Csv.write(outDir.resolve("detection_results.csv"), List.of("config", "homework", "starter_mode", "method",
                "model_id", "prompt_version", "comparison", "level", "id", "sub_a", "sub_b", "split", "label",
                "relation", "transformation", "amount_copied", "cohort_relation", "similarity", "threshold",
                "prediction", "outcome", "status", "evidence"), detections);
        writeStarterComparison(results);

        ObjectNode summary = JSON.createObjectNode();
        summary.put("evaluated_utc", Instant.now().toString());
        summary.put("thresholds_frozen_utc", frozen.path("frozen_utc").asText());
        summary.put("configurations", results.size());
        write(outDir.resolve(TEST_EVALUATION), summary);
        log(reevaluation ? "test_reevaluation (--allow-reevaluation)" : "test",
                Map.of("configurations", results.size()));
        return results;
    }

    private void verifyInputs(JsonNode frozenHashes, List<Path> inputs) {
        List<String> problems = new ArrayList<>();
        Map<String, String> now = new TreeMap<>();
        for (Path p : inputs) {
            now.put(p.toString(), sha256(p));
        }
        frozenHashes.fieldNames().forEachRemaining(f -> {
            if (!now.containsKey(f)) {
                problems.add("missing now: " + f);
            } else if (!now.get(f).equals(frozenHashes.path(f).asText())) {
                problems.add("changed since freeze: " + f);
            }
        });
        now.keySet().forEach(f -> {
            if (!frozenHashes.has(f)) {
                problems.add("not part of the freeze: " + f);
            }
        });
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Inputs differ from the frozen selection, so the test evaluation would not "
                    + "use the frozen setup:\n  " + String.join("\n  ", problems));
        }
    }

    // ================================================================== rows

    private static List<String> keyHeader() {
        return List.of("config", "homework", "starter_mode", "method", "model_id", "comparison", "level");
    }

    private static List<Object> keyCols(ConfigKey k) {
        return new ArrayList<>(List.of(k.id(), k.homework(), k.starterMode(), k.method(), k.modelId(), k.comparison(),
                k.level()));
    }

    private static List<String> sweepHeader() {
        List<String> h = new ArrayList<>(keyHeader());
        h.addAll(List.of("split", "threshold", "tp", "fp", "tn", "fn", "precision", "recall", "f1", "fpr", "fnr",
                "unresolved", "unresolved_flagged", "unresolved_flag_rate", "failed"));
        return h;
    }

    private static List<Object> sweepRow(ConfigKey k, String split, Counts c) {
        List<Object> r = keyCols(k);
        r.add(split);
        r.add(fmt(c.threshold(), 1));
        r.add(c.tp());
        r.add(c.fp());
        r.add(c.tn());
        r.add(c.fn());
        r.add(fmt(c.precision(), 4));
        r.add(fmt(c.recall(), 4));
        r.add(fmt(c.f1(), 4));
        r.add(fmt(c.fpr(), 4));
        r.add(fmt(c.fnr(), 4));
        r.add(c.unresolved());
        r.add(c.unresolvedFlagged());
        r.add(fmt(c.unresolvedFlagRate(), 4));
        r.add(c.failed());
        return r;
    }

    private void writeSelectionSummary(List<Selection> selections) {
        List<String> h = new ArrayList<>(keyHeader());
        h.addAll(List.of("status", "threshold", "val_positives", "val_negatives", "val_tp", "val_fp", "val_recall",
                "val_fpr", "val_precision", "val_unresolved_flag_rate"));
        List<List<Object>> rows = new ArrayList<>();
        for (Selection s : selections) {
            List<Object> r = keyCols(s.key());
            r.add(s.status().name());
            r.add(s.threshold() == null ? null : fmt(s.threshold(), 1));
            Counts c = s.validation() != null ? s.validation() : s.sweep().getFirst();
            r.add(c.positives());
            r.add(c.negatives());
            r.add(s.validation() == null ? null : c.tp());
            r.add(s.validation() == null ? null : c.fp());
            r.add(s.validation() == null ? null : fmt(c.recall(), 4));
            r.add(s.validation() == null ? null : fmt(c.fpr(), 4));
            r.add(s.validation() == null ? null : fmt(c.precision(), 4));
            r.add(s.validation() == null ? null : fmt(c.unresolvedFlagRate(), 4));
            rows.add(r);
        }
        Csv.write(outDir.resolve("selection_summary.csv"), h, rows);
    }

    private void writeTestResults(List<TestResult> results) {
        List<String> h = new ArrayList<>(keyHeader());
        h.addAll(List.of("threshold", "tp", "fp", "tn", "fn", "precision", "precision_ci_low", "precision_ci_high",
                "recall", "recall_ci_low", "recall_ci_high", "f1", "fpr", "fpr_ci_low", "fpr_ci_high", "fnr",
                "recall_bootstrap_low", "recall_bootstrap_high", "fpr_bootstrap_low", "fpr_bootstrap_high",
                "positive_clusters", "negative_clusters", "unresolved", "unresolved_flagged", "unresolved_flag_rate",
                "failed"));
        List<List<Object>> rows = new ArrayList<>();
        for (TestResult t : results) {
            Counts c = t.counts();
            List<Object> r = keyCols(t.key());
            r.add(fmt(t.threshold(), 1));
            r.add(c.tp());
            r.add(c.fp());
            r.add(c.tn());
            r.add(c.fn());
            r.add(fmt(c.precision(), 4));
            r.add(fmt(t.precisionCi()[0], 4));
            r.add(fmt(t.precisionCi()[1], 4));
            r.add(fmt(c.recall(), 4));
            r.add(fmt(t.recallCi()[0], 4));
            r.add(fmt(t.recallCi()[1], 4));
            r.add(fmt(c.f1(), 4));
            r.add(fmt(c.fpr(), 4));
            r.add(fmt(t.fprCi()[0], 4));
            r.add(fmt(t.fprCi()[1], 4));
            r.add(fmt(c.fnr(), 4));
            for (double b : t.bootstrap()) {
                r.add(fmt(b, 4));
            }
            r.add(t.positiveClusters());
            r.add(t.negativeClusters());
            r.add(c.unresolved());
            r.add(c.unresolvedFlagged());
            r.add(fmt(c.unresolvedFlagRate(), 4));
            r.add(c.failed());
            rows.add(r);
        }
        Csv.write(outDir.resolve("test_results.csv"), h, rows);
    }

    /** Recall by transformation / relation / amount copied / cohort relation, and FPR by code size and starter share. */
    private static List<List<Object>> breakdownRows(ConfigKey key, List<Observation> test, double t) {
        List<List<Object>> rows = new ArrayList<>();
        Map<String, Function<Observation, String>> dims = new LinkedHashMap<>();
        dims.put("transformation", o -> o.transformation() == null ? "(none)" : o.transformation().split("\\|")[0]);
        dims.put("relation", o -> o.relation() == null ? "(none)" : o.relation());
        dims.put("amount_copied", o -> amountBucket(o.amountCopied()));
        dims.put("cohort_relation", o -> o.cohortRelation() == null ? "(n/a)" : o.cohortRelation());
        for (Map.Entry<String, Function<Observation, String>> d : dims.entrySet()) {
            Map<String, int[]> agg = new TreeMap<>(); // positives, detected, negatives, fp
            for (Observation o : test) {
                if (o.truth() == Truth.UNRESOLVED) {
                    continue;
                }
                int[] a = agg.computeIfAbsent(d.getValue().apply(o), k -> new int[4]);
                if (o.truth() == Truth.POSITIVE) {
                    a[0]++;
                    a[1] += o.flagged(t) ? 1 : 0;
                } else {
                    a[2]++;
                    a[3] += o.flagged(t) ? 1 : 0;
                }
            }
            agg.forEach((value, a) -> {
                List<Object> r = keyCols(key);
                r.add(fmt(t, 1));
                r.add(d.getKey());
                r.add(value);
                r.add(a[0]);
                r.add(a[1]);
                r.add(a[0] == 0 ? null : fmt((double) a[1] / a[0], 4));
                r.add(a[2]);
                r.add(a[3]);
                r.add(a[2] == 0 ? null : fmt((double) a[3] / a[2], 4));
                rows.add(r);
            });
        }
        return rows;
    }

    static String amountBucket(Double amount) {
        if (amount == null) {
            return "(n/a)";
        }
        if (amount >= 0.999) {
            return "whole program";
        }
        if (amount < 0.375) {
            return "under 37.5%";
        }
        return amount < 0.625 ? "37.5-62.5%" : "62.5-99.9%";
    }

    private static List<String> errorHeader() {
        List<String> h = new ArrayList<>(keyHeader());
        h.addAll(List.of("split", "error", "id", "sub_a", "sub_b", "similarity", "threshold", "transformation",
                "relation", "amount_copied", "cohort_relation", "starter_share_a", "starter_share_b", "own_lines_a",
                "own_lines_b", "hint", "status"));
        return h;
    }

    /** False positives and false negatives at the threshold, with features that help explain them. */
    private static List<List<Object>> errorRows(ConfigKey key, String split, List<Observation> obs, double t,
                                               EvalData.Loaded data) {
        List<List<Object>> rows = new ArrayList<>();
        for (Observation o : obs) {
            boolean flag = o.flagged(t);
            String error = o.truth() == Truth.NEGATIVE && flag ? "FALSE_POSITIVE"
                    : o.truth() == Truth.POSITIVE && !flag ? "FALSE_NEGATIVE" : null;
            if (error == null) {
                continue;
            }
            EvalData.Features fa = data.features(key, o.subA());
            EvalData.Features fb = o.subB() == null ? null : data.features(key, o.subB());
            List<Object> r = keyCols(key);
            r.add(split);
            r.add(error);
            r.add(o.id());
            r.add(o.subA());
            r.add(o.subB());
            r.add(o.score() == null ? null : fmt(o.score(), 2));
            r.add(fmt(t, 1));
            r.add(o.transformation());
            r.add(o.relation());
            r.add(o.amountCopied() == null ? null : fmt(o.amountCopied(), 3));
            r.add(o.cohortRelation());
            r.add(fa == null ? null : fmt(fa.starterFraction(), 3));
            r.add(fb == null ? null : fmt(fb.starterFraction(), 3));
            r.add(fa == null ? null : fa.ownLines());
            r.add(fb == null ? null : fb.ownLines());
            r.add(hint(error, o, fa, fb));
            r.add(o.status());
            rows.add(r);
        }
        return rows;
    }

    /** First-pass explanation; conventional-algorithm overlap still needs a human look at the code. */
    private static String hint(String error, Observation o, EvalData.Features fa, EvalData.Features fb) {
        if (o.score() == null) {
            return "comparison failed: " + o.status();
        }
        if (error.equals("FALSE_NEGATIVE")) {
            if (o.amountCopied() != null && o.amountCopied() < 0.625 && !"SOURCE".equals(o.relation())) {
                return "small shared share (" + o.relation() + ")";
            }
            return o.amountCopied() != null && o.amountCopied() < 0.999 ? "partial copy" : "transformed copy";
        }
        int minOwn = Math.min(fa == null ? Integer.MAX_VALUE : fa.ownLines(), fb == null ? Integer.MAX_VALUE : fb.ownLines());
        double maxStarter = Math.max(fa == null ? 0 : fa.starterFraction(), fb == null ? 0 : fb.starterFraction());
        if (minOwn < 30) {
            return "short student-written code";
        }
        if (maxStarter > 0.5) {
            return "starter-heavy";
        }
        return "review: conventional algorithm?";
    }

    private static List<Object> detectionRow(ConfigKey key, Observation o, double t) {
        boolean flag = o.flagged(t);
        String outcome = switch (o.truth()) {
            case POSITIVE -> flag ? "TP" : "FN";
            case NEGATIVE -> flag ? "FP" : "TN";
            case UNRESOLVED -> flag ? "FLAGGED_UNRESOLVED" : "UNFLAGGED_UNRESOLVED";
        };
        List<Object> r = new ArrayList<>(List.of(key.id(), key.homework(), key.starterMode(), key.method(),
                key.modelId(), key.promptVersion(), key.comparison(), key.level()));
        r.add(o.id());
        r.add(o.subA());
        r.add(o.subB());
        r.add(o.split());
        r.add(o.truth().name());
        r.add(o.relation());
        r.add(o.transformation());
        r.add(o.amountCopied() == null ? null : fmt(o.amountCopied(), 3));
        r.add(o.cohortRelation());
        r.add(o.score() == null ? null : fmt(o.score(), 2));
        r.add(fmt(t, 1));
        r.add(flag ? "FLAG" : "NO_FLAG");
        r.add(outcome);
        r.add(o.status());
        r.add(o.evidence());
        return r;
    }

    /** Side-by-side STRIP vs KEEP for configurations that differ only by starter mode. */
    private void writeStarterComparison(List<TestResult> results) {
        Map<String, Map<String, TestResult>> byRest = new TreeMap<>();
        for (TestResult t : results) {
            ConfigKey k = t.key();
            String rest = String.join("/", k.homework(), k.method(), k.modelId(), k.comparison(), k.level());
            byRest.computeIfAbsent(rest, x -> new TreeMap<>()).put(k.starterMode(), t);
        }
        List<List<Object>> rows = new ArrayList<>();
        byRest.forEach((rest, modes) -> {
            if (modes.size() < 2) {
                return;
            }
            TestResult s = modes.get("STRIP");
            TestResult k = modes.get("KEEP");
            if (s == null || k == null) {
                return;
            }
            rows.add(List.of(rest, fmt(s.threshold(), 1), fmt(k.threshold(), 1), fmt(s.counts().recall(), 4),
                    fmt(k.counts().recall(), 4), fmt(s.counts().fpr(), 4), fmt(k.counts().fpr(), 4),
                    fmt(s.counts().unresolvedFlagRate(), 4), fmt(k.counts().unresolvedFlagRate(), 4)));
        });
        Csv.write(outDir.resolve("starter_mode_comparison.csv"), List.of("configuration", "threshold_strip",
                "threshold_keep", "test_recall_strip", "test_recall_keep", "test_fpr_strip", "test_fpr_keep",
                "unresolved_flag_rate_strip", "unresolved_flag_rate_keep"), rows);
    }

    // ================================================================== util

    private static List<Observation> bySplit(List<Observation> obs, String split) {
        return obs.stream().filter(o -> o.split().equals(split)).toList();
    }

    private static void putKey(ObjectNode n, ConfigKey k) {
        n.put("config", k.id()).put("homework", k.homework()).put("starter_mode", k.starterMode())
                .put("method", k.method()).put("model_id", k.modelId()).put("prompt_version", k.promptVersion())
                .put("comparison", k.comparison()).put("level", k.level());
    }

    static String fmt(double v, int decimals) {
        return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    private void log(String event, Map<String, Object> details) {
        ObjectNode n = JSONL.createObjectNode();
        n.put("utc", Instant.now().toString());
        n.put("event", event);
        n.put("labels", labelsDir.toString());
        ArrayNode runs = n.putArray("runs");
        runDirs.forEach(r -> runs.add(r.toString()));
        details.forEach((k, v) -> n.put(k, String.valueOf(v)));
        try {
            Files.createDirectories(outDir);
            Files.writeString(outDir.resolve(LOG), n + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path p, JsonNode n) {
        try {
            Files.createDirectories(p.toAbsolutePath().getParent());
            JSON.writeValue(p.toFile(), n);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode readJson(Path p) {
        try {
            return JSON.readTree(p.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String sha256(Path p) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot hash " + p, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
