package aiaa.eval;

import aiaa.data.HomeworkSpec;
import aiaa.eval.Observation.Truth;
import aiaa.pdm.Csv;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Joins PDM run outputs (comparisons.csv, submission_scores.csv, inventory.csv) with the
 * ground-truth labels (pair_labels.csv, submission_labels.csv) into observations per
 * configuration. Cross-split and EXCLUDED pairs are dropped; failed comparisons are kept with a
 * null score so they count as missed detections.
 */
public final class EvalData {

    /** Code features used to explain false positives: share of starter lines, size of student-written code. */
    public record Features(double starterFraction, int ownLines) {
    }

    public record Loaded(Map<ConfigKey, List<Observation>> observations, List<Path> inputs,
                         Map<String, Features> features, List<String> warnings) {
        /** features key: homework|starterMode|submission */
        public Features features(ConfigKey k, String submission) {
            return features.get(k.homework() + "|" + k.starterMode() + "|" + submission);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private EvalData() {
    }

    public static Loaded load(Path labelsDir, List<Path> runDirs) {
        Map<ConfigKey, List<Observation>> obs = new LinkedHashMap<>();
        Map<ConfigKey, Path> origin = new HashMap<>();
        List<Path> inputs = new ArrayList<>();
        Map<String, Features> features = new HashMap<>();
        List<String> warnings = new ArrayList<>();
        inputs.add(labelsDir.resolve("split_manifest.csv"));

        for (Path run : runDirs) {
            JsonNode manifest = readJson(run.resolve("run_manifest.json"));
            String assignment = manifest.path("assignment").asText();
            String starterMode = manifest.path("input").path("starter_mode").asText();
            String hw = homeworkFor(labelsDir, assignment);
            Path hwLabels = labelsDir.resolve(hw);
            Path pairFile = hwLabels.resolve("pair_labels.csv");
            Path subFile = hwLabels.resolve("submission_labels.csv");
            if (!Files.exists(pairFile)) {
                throw new IllegalArgumentException("No labels for " + hw + " at " + pairFile);
            }
            for (Path p : List.of(pairFile, subFile)) {
                if (!inputs.contains(p)) {
                    inputs.add(p);
                }
            }
            Path comparisons = run.resolve("comparisons.csv");
            Path scores = run.resolve("submission_scores.csv");
            inputs.add(comparisons);
            inputs.add(scores);

            Map<String, Map<String, String>> pairLabels = new HashMap<>();
            for (Map<String, String> r : Csv.read(pairFile)) {
                pairLabels.put(r.get("comparison") + "|" + r.get("pair_key"), r);
            }
            Map<String, Map<String, String>> subLabels = new HashMap<>();
            for (Map<String, String> r : Csv.read(subFile)) {
                subLabels.put(r.get("submission"), r);
            }
            for (Map<String, String> r : Csv.read(run.resolve("inventory.csv"))) {
                int before = parseInt(r.get("substantive_lines_before"));
                int removed = parseInt(r.get("starter_lines_removed"));
                features.put(hw + "|" + starterMode + "|" + r.get("id"),
                        new Features(before == 0 ? 0 : (double) removed / before, parseInt(r.get("substantive_lines_after"))));
            }

            Map<String, String[]> modelOf = new HashMap<>(); // method|comparison -> {model, prompt}
            // student level: best partner per method|comparison|submission over ALL splits, skipping
            // EXCLUDED (control-control, same author) pairs; recomputed here instead of the PDM's max
            Map<String, Object[]> best = new HashMap<>(); // key -> {score, partner}
            int unlabeled = 0;
            Map<ConfigKey, List<Observation>> fromRun = new LinkedHashMap<>();
            for (Map<String, String> r : Csv.read(comparisons)) {
                String a = r.get("sub_a");
                String b = r.get("sub_b");
                String pairKey = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
                Map<String, String> l = pairLabels.get(r.get("comparison") + "|" + pairKey);
                modelOf.putIfAbsent(r.get("method") + "|" + r.get("comparison"),
                        new String[]{r.get("model_id"), r.get("prompt_version")});
                if (l == null) {
                    unlabeled++;
                    continue;
                }
                if (l.get("label").equals("EXCLUDED")) {
                    continue;
                }
                if ("OK".equals(r.get("status"))) {
                    double sim = parseDouble(r.get("similarity"));
                    String prefix = r.get("method") + "|" + r.get("comparison") + "|";
                    updateBest(best, prefix + a, sim, b);
                    if (r.get("comparison").equals("PEER")) {
                        updateBest(best, prefix + b, sim, a);
                    }
                }
                if (l.get("split").equals("CROSS_SPLIT")) {
                    continue;
                }
                Truth truth = truth(l.get("label"));
                String cluster = switch (truth) {
                    case NEGATIVE -> l.get("kind_a").equals("CONTROL") ? l.get("sub_a") : l.get("sub_b");
                    default -> family(subLabels, l.get("sub_a"));
                };
                ConfigKey key = new ConfigKey(hw, starterMode, r.get("method"), r.get("model_id"),
                        r.get("prompt_version"), r.get("comparison"), "PAIR");
                Double score = "OK".equals(r.get("status")) ? parseDouble(r.get("similarity")) : null;
                fromRun.computeIfAbsent(key, k -> new ArrayList<>()).add(new Observation(pairKey, a, b, l.get("split"),
                        truth, score, cluster, emptyToNull(l.get("transformation")), l.get("relation"),
                        parseDouble(l.get("amount_copied")), r.get("cohort_relation"), emptyToNull(r.get("evidence")),
                        r.get("status")));
            }
            if (unlabeled > 0) {
                warnings.add(run + ": " + unlabeled + " comparisons have no label (labels and run out of sync?)");
            }

            boolean anyAi = Csv.read(scores).stream().anyMatch(r -> parseInt(r.get("ai_ok")) > 0);
            for (Map<String, String> r : Csv.read(scores)) {
                Map<String, String> l = subLabels.get(r.get("submission"));
                if (l == null) {
                    continue;
                }
                String label = l.get("label");
                String method = r.get("method");
                Object[] peer = best.get(method + "|PEER|" + r.get("submission"));
                Object[] ai = best.get(method + "|AI_REFERENCE|" + r.get("submission"));
                addStudent(fromRun, hw, starterMode, method, "PEER", modelOf, l, r.get("submission"),
                        peer == null ? null : (String) peer[1], peer == null ? null : String.valueOf(peer[0]),
                        switch (label) {
                            case "COPY" -> Truth.POSITIVE;
                            case "NEGATIVE" -> Truth.NEGATIVE;
                            case "UNRESOLVED" -> Truth.UNRESOLVED;
                            default -> null; // AI copies are not peer-copy cases
                        });
                if (anyAi) {
                    addStudent(fromRun, hw, starterMode, method, "AI_REFERENCE", modelOf, l, r.get("submission"),
                            ai == null ? null : (String) ai[1], ai == null ? null : String.valueOf(ai[0]),
                            switch (label) {
                                case "AI_COPY" -> Truth.POSITIVE;
                                case "NEGATIVE" -> Truth.NEGATIVE;
                                default -> Truth.UNRESOLVED;
                            });
                }
            }

            for (Map.Entry<ConfigKey, List<Observation>> e : fromRun.entrySet()) {
                Path previous = origin.putIfAbsent(e.getKey(), run);
                if (previous != null) {
                    throw new IllegalArgumentException("Runs " + previous + " and " + run + " have the same configuration "
                            + e.getKey().id() + "; evaluate repeated runs separately");
                }
                obs.put(e.getKey(), e.getValue());
            }
        }
        return new Loaded(obs, inputs, features, warnings);
    }

    private static void addStudent(Map<ConfigKey, List<Observation>> out, String hw, String starterMode, String method,
                                   String comparison, Map<String, String[]> modelOf, Map<String, String> label,
                                   String submission, String partner, String score, Truth truth) {
        if (truth == null) {
            return;
        }
        String[] model = modelOf.getOrDefault(method + "|" + comparison, new String[]{method, ""});
        ConfigKey key = new ConfigKey(hw, starterMode, method, model[0], model[1], comparison, "STUDENT");
        String cluster = truth == Truth.NEGATIVE ? submission : label.get("family");
        out.computeIfAbsent(key, k -> new ArrayList<>()).add(new Observation(submission, submission,
                emptyToNull(partner), label.get("split"), truth, parseDouble(score), cluster,
                emptyToNull(label.get("transformation")), truth == Truth.NEGATIVE ? "CONTROL" : null,
                parseDouble(label.get("amount_copied")), null, null, score == null || score.isBlank() ? "NO_SCORE" : "OK"));
    }

    private static void updateBest(Map<String, Object[]> best, String key, double score, String partner) {
        Object[] cur = best.get(key);
        if (cur == null || score > (Double) cur[0]) {
            best.put(key, new Object[]{score, partner});
        }
    }

    private static Truth truth(String label) {
        return switch (label) {
            case "COPY" -> Truth.POSITIVE;
            case "NEGATIVE" -> Truth.NEGATIVE;
            default -> Truth.UNRESOLVED;
        };
    }

    private static String family(Map<String, Map<String, String>> subLabels, String id) {
        Map<String, String> l = subLabels.get(id);
        return l == null ? id : l.get("family");
    }

    /** Label folder for a run: the assignment itself (Hw1) or the homework a cohort run belongs to (F25Hw1). */
    static String homeworkFor(Path labelsDir, String assignment) {
        if (Files.isDirectory(labelsDir.resolve(assignment))) {
            return assignment;
        }
        for (HomeworkSpec h : HomeworkSpec.KNOWN) {
            if (assignment.endsWith(h.name()) && Files.isDirectory(labelsDir.resolve(h.name()))) {
                return h.name();
            }
        }
        throw new IllegalArgumentException("No labels folder for assignment " + assignment + " under " + labelsDir);
    }

    private static JsonNode readJson(Path p) {
        try {
            return JSON.readTree(p.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + p + " (is this a PDM output folder?)", e);
        }
    }

    private static Double parseDouble(String s) {
        return s == null || s.isBlank() ? null : Double.parseDouble(s);
    }

    private static int parseInt(String s) {
        return s == null || s.isBlank() ? 0 : Integer.parseInt(s);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
