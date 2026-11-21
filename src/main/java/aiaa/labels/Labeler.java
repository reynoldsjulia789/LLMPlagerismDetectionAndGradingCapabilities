package aiaa.labels;

import aiaa.data.DataLayout;
import aiaa.pdm.Csv;
import aiaa.pdm.SubmissionIds;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds ground-truth labels and the dev / validation / test split.
 *
 * <h2>Submission kinds</h2>
 * ORIGINAL (historical submission, provenance unknown), VARIANT (synthetic copy, provenance in the
 * generator's manifest.csv), CONTROL (negative control, provenance in negative-controls/).
 *
 * <h2>Pair labels (peer comparisons)</h2>
 * <ul>
 *   <li>COPY: one side was derived from the other (relation SOURCE, or HOST for a partial copy's host),
 *       or both were derived from a common submission (SIBLING). {@code amount_copied} is the fraction of
 *       the derived side's own code taken from the other (for siblings: the smaller of the two shares).</li>
 *   <li>NEGATIVE: exactly one side is a negative control (no submission can have been copied from it).</li>
 *   <li>EXCLUDED: both sides are controls (same author, so not independent).</li>
 *   <li>UNRESOLVED: everything else, notably original vs original (no verified provenance).</li>
 * </ul>
 * AI-reference rows follow the same rules with the reference as the other side.
 *
 * <h2>Submission labels</h2>
 * COPY (peer-copy variant), AI_COPY (variant of an AI reference), NEGATIVE (control),
 * UNRESOLVED (original).
 *
 * <h2>Split</h2>
 * Families (connected components of "same student", "derived from" and "partial-copy host of")
 * never straddle splits. Families are assigned greedily, largest first with a seeded tie-break,
 * to keep every homework near the target fractions. Controls are assigned per homework in the
 * proportions of the targets. Pairs whose two sides are in different splits are CROSS_SPLIT and
 * must be left out of metrics.
 */
public final class Labeler {

    public enum Kind { ORIGINAL, VARIANT, CONTROL }

    public enum Split { DEV, VALIDATION, TEST }

    record Sub(String id, String homework, String cohort, Kind kind, Path folder) {
    }

    /** One way a variant derives from another submission or reference. */
    record Link(String ancestor, String relation, String transformation, double amount) {
    }

    public record Summary(int submissions, int families, Map<String, Map<Split, Integer>> perHomework,
                          Map<String, Integer> pairLabels, int crossSplitPairs, int originalsWithoutStudentKey) {
    }

    private static final Pattern STUDENT_FOLDER = Pattern.compile("^\\d+_(\\d+)_");
    private static final Pattern VARIANT_ID = Pattern.compile(".*-V\\d+");
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final LabelConfig config;

    private final Map<String, List<Sub>> subsByHomework = new LinkedHashMap<>();
    private final Map<String, Sub> subs = new LinkedHashMap<>();
    private final Map<String, List<Link>> ancestry = new HashMap<>();
    private final Map<String, Set<String>> referencesByHomework = new TreeMap<>();
    private final Map<String, Split> splitOf = new HashMap<>();
    private final Map<String, String> familyOf = new HashMap<>();
    private int withoutStudentKey;

    public Labeler(LabelConfig config) {
        this.config = config;
    }

    public Summary run() {
        Instant started = Instant.now();
        List<String> homeworks = config.homeworks().isEmpty() ? discoverHomeworks() : config.homeworks();
        Map<String, String> studentKeyOf = new HashMap<>();
        for (String hw : homeworks) {
            load(hw, studentKeyOf);
        }
        Map<String, List<String>> families = buildFamilies(studentKeyOf);
        assignSplits(families, homeworks);

        Map<String, Integer> pairCounts = new TreeMap<>();
        int[] crossSplit = {0};
        for (String hw : homeworks) {
            writeHomework(hw, pairCounts, crossSplit);
        }
        writeSplitManifest();
        Map<String, Map<Split, Integer>> perHw = countsPerHomework();
        writeLog(started, homeworks, families, perHw, pairCounts, crossSplit[0]);
        return new Summary(subs.size(), families.size(), perHw, pairCounts, crossSplit[0], withoutStudentKey);
    }

    // ------------------------------------------------------------------ loading

    private List<String> discoverHomeworks() {
        Path submissions = config.dataRoot().resolve(DataLayout.SUBMISSIONS);
        if (!Files.isDirectory(submissions)) {
            throw new IllegalArgumentException("No " + DataLayout.SUBMISSIONS + " folder under " + config.dataRoot());
        }
        return DataLayout.subdirectories(submissions).stream().map(p -> p.getFileName().toString()).toList();
    }

    private void load(String homework, Map<String, String> studentKeyOf) {
        DataLayout.Selection sel = DataLayout.resolve(config.dataRoot(), homework, null);
        String hw = sel.homework().name();
        List<DataLayout.Cohort> cohorts = new ArrayList<>(sel.cohorts());
        DataLayout.controls(config.controlsRoot(), hw).ifPresent(cohorts::add);
        List<Sub> list = new ArrayList<>();
        for (DataLayout.Cohort cohort : cohorts) {
            boolean controls = cohort.label().equals(DataLayout.CONTROLS_COHORT);
            Map<String, String> privateMap = controls ? Map.of() : readIdMap(cohort.label());
            Map<String, String[]> manifest = controls ? Map.of() : readManifest(cohort.dir());
            for (SubmissionIds.Entry e : SubmissionIds.assign(cohort, false)) {
                Kind kind = controls ? Kind.CONTROL : VARIANT_ID.matcher(e.id()).matches() ? Kind.VARIANT : Kind.ORIGINAL;
                Sub s = new Sub(e.id(), hw, cohort.label(), kind, e.folder());
                if (subs.putIfAbsent(s.id(), s) != null) {
                    throw new IllegalArgumentException("Duplicate submission ID " + s.id());
                }
                list.add(s);
                if (kind == Kind.ORIGINAL) {
                    String folder = privateMap.getOrDefault(e.id(), e.folder().getFileName().toString());
                    Matcher m = STUDENT_FOLDER.matcher(folder);
                    if (m.find()) {
                        studentKeyOf.put(s.id(), config.groupStudentsAcrossHomeworks() ? m.group(1) : hw + ":" + m.group(1));
                    } else {
                        withoutStudentKey++;
                    }
                } else if (kind == Kind.VARIANT) {
                    String[] row = manifest.get(s.id());
                    if (row == null) {
                        throw new IllegalArgumentException("Variant " + s.id() + " has no row in "
                                + cohort.dir().resolve("manifest.csv"));
                    }
                    addAncestry(hw, s.id(), row);
                }
            }
        }
        subsByHomework.put(hw, list);
    }

    /** row = [source_kind, source_id, host_id, transformation, amount_copied] */
    private void addAncestry(String hw, String variant, String[] row) {
        String sourceKind = row[0];
        String source = row[1];
        String host = row[2];
        String transformation = row[3];
        double amount = row[4].isBlank() ? 1.0 : Double.parseDouble(row[4]);
        List<Link> links = new ArrayList<>();
        links.add(new Link(source, "SOURCE", transformation, amount));
        if (!host.isBlank()) {
            links.add(new Link(host, "HOST", transformation, 1.0 - amount));
        }
        ancestry.put(variant, links);
        if ("ai_reference".equals(sourceKind)) {
            referencesByHomework.computeIfAbsent(hw, k -> new TreeSet<>()).add(source);
        }
    }

    private Map<String, String> readIdMap(String cohortLabel) {
        if (config.privateDir() == null) {
            return Map.of();
        }
        Path file = config.privateDir().resolve("synth_id_map_" + cohortLabel + ".csv");
        Map<String, String> out = new HashMap<>();
        for (Map<String, String> r : readCsv(file)) {
            out.put(r.get("anon_id"), r.get("source_folder"));
        }
        return out;
    }

    private Map<String, String[]> readManifest(Path cohortDir) {
        Map<String, String[]> out = new HashMap<>();
        for (Map<String, String> r : readCsv(cohortDir.resolve("manifest.csv"))) {
            if ("OK".equals(r.get("status")) && !r.get("variant_id").isBlank()) {
                out.put(r.get("variant_id"), new String[]{r.get("source_kind"), r.get("source_id"),
                        r.getOrDefault("host_id", ""), r.get("transformation"), r.getOrDefault("amount_copied", "")});
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ families and split

    private Map<String, List<String>> buildFamilies(Map<String, String> studentKeyOf) {
        UnionFind uf = new UnionFind();
        subs.keySet().forEach(uf::add);
        Map<String, String> firstOfStudent = new HashMap<>();
        studentKeyOf.forEach((id, key) -> {
            String first = firstOfStudent.putIfAbsent(key, id);
            if (first != null) {
                uf.union(first, id);
            }
        });
        ancestry.forEach((variant, links) -> {
            for (Link l : links) {
                // references are not submissions: their copies are kept together via a synthetic node
                String node = subs.containsKey(l.ancestor()) ? l.ancestor()
                        : "REF:" + subs.get(variant).homework() + ":" + l.ancestor();
                uf.add(node);
                uf.union(variant, node);
            }
        });
        Map<String, List<String>> byRoot = new TreeMap<>();
        for (String id : subs.keySet()) {
            byRoot.computeIfAbsent(uf.find(id), k -> new ArrayList<>()).add(id);
        }
        Map<String, List<String>> families = new LinkedHashMap<>();
        int n = 0;
        for (List<String> members : byRoot.values()) {
            String family = String.format("F%03d", ++n);
            families.put(family, members);
            members.forEach(m -> familyOf.put(m, family));
        }
        return families;
    }

    private void assignSplits(Map<String, List<String>> families, List<String> homeworks) {
        Random rnd = new Random(config.seed());
        double[] f = config.fractions();

        // Controls: per homework, in the target proportions (e.g. 4 -> 2 / 1 / 1).
        for (String hw : homeworks) {
            List<String> controls = new ArrayList<>(subsByHomework.getOrDefault(hw, List.of()).stream()
                    .filter(s -> s.kind() == Kind.CONTROL).map(Sub::id).toList());
            Collections.shuffle(controls, rnd);
            int n = controls.size();
            int test = n >= 3 ? Math.max(1, (int) Math.round(n * f[2])) : 0;
            int val = n >= 3 ? Math.max(1, (int) Math.round(n * f[1])) : (n == 2 ? 1 : 0);
            for (int i = 0; i < n; i++) {
                Split s = i < n - test - val ? Split.DEV : i < n - test ? Split.VALIDATION : Split.TEST;
                splitOf.put(controls.get(i), s);
            }
        }

        // Everything else: whole families, largest first, seeded tie-break.
        List<List<String>> order = new ArrayList<>();
        families.values().forEach(members -> {
            if (members.stream().noneMatch(m -> subs.get(m).kind() == Kind.CONTROL)) {
                order.add(members);
            }
        });
        Collections.shuffle(order, rnd);
        order.sort(Comparator.comparingInt((List<String> m) -> m.size()).reversed());

        Map<String, Integer> totals = new HashMap<>();
        order.forEach(m -> m.forEach(id -> totals.merge(subs.get(id).homework(), 1, Integer::sum)));
        Map<String, int[]> current = new HashMap<>();
        for (List<String> members : order) {
            Map<String, Integer> fam = new HashMap<>();
            members.forEach(id -> fam.merge(subs.get(id).homework(), 1, Integer::sum));
            int best = 0;
            double bestCost = Double.MAX_VALUE;
            for (int s = 0; s < 3; s++) {
                double cost = 0;
                for (Map.Entry<String, Integer> e : fam.entrySet()) {
                    int[] cur = current.computeIfAbsent(e.getKey(), k -> new int[3]);
                    double target = f[s] * totals.get(e.getKey());
                    double before = cur[s] - target;
                    double after = cur[s] + e.getValue() - target;
                    cost += after * after - before * before;
                }
                if (cost < bestCost - 1e-9) {
                    bestCost = cost;
                    best = s;
                }
            }
            Split chosen = Split.values()[best];
            for (String id : members) {
                splitOf.put(id, chosen);
                current.computeIfAbsent(subs.get(id).homework(), k -> new int[3])[best]++;
            }
        }
    }

    // ------------------------------------------------------------------ labels

    record PairLabel(String label, String relation, String transformation, Double amount) {
    }

    PairLabel peerLabel(Sub a, Sub b) {
        if (a.kind() == Kind.CONTROL && b.kind() == Kind.CONTROL) {
            return new PairLabel("EXCLUDED", "CONTROL_PAIR", null, null);
        }
        PairLabel direct = direct(a, b);
        if (direct == null) {
            direct = direct(b, a);
        }
        if (direct != null) {
            return direct;
        }
        PairLabel sibling = sibling(a.id(), b.id());
        if (sibling != null) {
            return sibling;
        }
        if (a.kind() == Kind.CONTROL || b.kind() == Kind.CONTROL) {
            return new PairLabel("NEGATIVE", "CONTROL", null, null);
        }
        return new PairLabel("UNRESOLVED", "NONE", null, null);
    }

    /** derived was made (partly) from other. */
    private PairLabel direct(Sub derived, Sub other) {
        for (Link l : ancestry.getOrDefault(derived.id(), List.of())) {
            if (l.ancestor().equals(other.id())) {
                return new PairLabel("COPY", l.relation(), l.transformation(), l.amount());
            }
        }
        return null;
    }

    private PairLabel sibling(String a, String b) {
        PairLabel best = null;
        for (Link la : ancestry.getOrDefault(a, List.of())) {
            for (Link lb : ancestry.getOrDefault(b, List.of())) {
                if (la.ancestor().equals(lb.ancestor())) {
                    double shared = Math.min(la.amount(), lb.amount());
                    if (best == null || shared > best.amount()) {
                        best = new PairLabel("COPY", "SIBLING", la.transformation() + "|" + lb.transformation(), shared);
                    }
                }
            }
        }
        return best;
    }

    PairLabel referenceLabel(Sub s, String reference) {
        for (Link l : ancestry.getOrDefault(s.id(), List.of())) {
            if (l.ancestor().equals(reference)) {
                return new PairLabel("COPY", l.relation(), l.transformation(), l.amount());
            }
        }
        if (s.kind() == Kind.CONTROL) {
            return new PairLabel("NEGATIVE", "CONTROL", null, null);
        }
        return new PairLabel("UNRESOLVED", "NONE", null, null);
    }

    private String submissionLabel(Sub s) {
        return switch (s.kind()) {
            case CONTROL -> "NEGATIVE";
            case ORIGINAL -> "UNRESOLVED";
            case VARIANT -> {
                Link source = ancestry.get(s.id()).getFirst();
                yield subs.containsKey(source.ancestor()) ? "COPY" : "AI_COPY";
            }
        };
    }

    private static String labelSource(PairLabel l) {
        return switch (l.label()) {
            case "COPY" -> "synthetic manifest";
            case "NEGATIVE", "EXCLUDED" -> "negative-control provenance";
            default -> "none";
        };
    }

    // ------------------------------------------------------------------ output

    private void writeHomework(String hw, Map<String, Integer> pairCounts, int[] crossSplit) {
        List<Sub> list = subsByHomework.get(hw);
        List<String> header = List.of("homework", "pair_key", "comparison", "sub_a", "sub_b", "kind_a", "kind_b",
                "cohort_a", "cohort_b", "split_a", "split_b", "split", "label", "relation", "transformation",
                "amount_copied", "label_source");
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                Sub a = list.get(i);
                Sub b = list.get(j);
                PairLabel l = peerLabel(a, b);
                String split = splitOf.get(a.id()) == splitOf.get(b.id()) ? splitOf.get(a.id()).name() : "CROSS_SPLIT";
                count(pairCounts, crossSplit, split, l);
                rows.add(row(hw, "PEER", a, b.id(), b.kind().name(), b.cohort(), splitOf.get(b.id()).name(), split, l));
            }
        }
        for (String ref : referencesByHomework.getOrDefault(hw, Set.of())) {
            for (Sub s : list) {
                PairLabel l = referenceLabel(s, ref);
                String split = splitOf.get(s.id()).name();
                count(pairCounts, crossSplit, split, l);
                rows.add(row(hw, "AI_REFERENCE", s, ref, "AI_REFERENCE", "", "", split, l));
            }
        }
        Path dir = config.outDir().resolve(hw);
        Csv.write(dir.resolve("pair_labels.csv"), header, rows);

        List<List<Object>> subRows = new ArrayList<>();
        for (Sub s : list) {
            List<Link> links = ancestry.getOrDefault(s.id(), List.of());
            Link source = links.isEmpty() ? null : links.getFirst();
            Link host = links.size() > 1 ? links.get(1) : null;
            List<Object> r = new ArrayList<>();
            r.add(hw);
            r.add(s.id());
            r.add(s.cohort());
            r.add(s.kind().name());
            r.add(submissionLabel(s));
            r.add(source == null ? null : source.transformation());
            r.add(source == null ? null : source.ancestor());
            r.add(host == null ? null : host.ancestor());
            r.add(source == null ? null : source.amount());
            r.add(familyOf.get(s.id()));
            r.add(splitOf.get(s.id()).name());
            subRows.add(r);
        }
        Csv.write(dir.resolve("submission_labels.csv"), List.of("homework", "submission", "cohort", "kind", "label",
                "transformation", "source_id", "host_id", "amount_copied", "family", "split"), subRows);
    }

    private List<Object> row(String hw, String comparison, Sub a, String bId, String bKind, String bCohort,
                             String bSplit, String split, PairLabel l) {
        List<Object> r = new ArrayList<>();
        r.add(hw);
        r.add(a.id().compareTo(bId) < 0 ? a.id() + "|" + bId : bId + "|" + a.id());
        r.add(comparison);
        r.add(a.id());
        r.add(bId);
        r.add(a.kind().name());
        r.add(bKind);
        r.add(a.cohort());
        r.add(bCohort);
        r.add(splitOf.get(a.id()).name());
        r.add(bSplit);
        r.add(split);
        r.add(l.label());
        r.add(l.relation());
        r.add(l.transformation());
        r.add(l.amount());
        r.add(labelSource(l));
        return r;
    }

    private void count(Map<String, Integer> pairCounts, int[] crossSplit, String split, PairLabel l) {
        if (split.equals("CROSS_SPLIT")) {
            crossSplit[0]++;
        } else {
            pairCounts.merge(split + "/" + l.label(), 1, Integer::sum);
        }
    }

    private void writeSplitManifest() {
        List<List<Object>> rows = new ArrayList<>();
        for (Sub s : subs.values()) {
            rows.add(List.of(s.id(), s.homework(), s.cohort(), s.kind().name(), familyOf.get(s.id()),
                    splitOf.get(s.id()).name()));
        }
        Csv.write(config.outDir().resolve("split_manifest.csv"),
                List.of("submission", "homework", "cohort", "kind", "family", "split"), rows);
    }

    private Map<String, Map<Split, Integer>> countsPerHomework() {
        Map<String, Map<Split, Integer>> out = new TreeMap<>();
        for (Sub s : subs.values()) {
            out.computeIfAbsent(s.homework(), k -> new EnumMap<>(Split.class)).merge(splitOf.get(s.id()), 1, Integer::sum);
        }
        return out;
    }

    private void writeLog(Instant started, List<String> homeworks, Map<String, List<String>> families,
                          Map<String, Map<Split, Integer>> perHw, Map<String, Integer> pairCounts, int crossSplit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("created_utc", started.toString());
        m.put("data_root", config.dataRoot().toString());
        m.put("controls_root", config.controlsRoot() == null ? null : config.controlsRoot().toString());
        m.put("homeworks", homeworks);
        m.put("seed", config.seed());
        m.put("target_fractions", Map.of("DEV", config.fractions()[0], "VALIDATION", config.fractions()[1],
                "TEST", config.fractions()[2]));
        m.put("grouping", "families = connected components of same student ("
                + (config.groupStudentsAcrossHomeworks() ? "across homeworks and terms" : "across terms, within a homework")
                + "), derived-from and partial-copy host; no family straddles splits");
        m.put("group_students_across_homeworks", config.groupStudentsAcrossHomeworks());
        m.put("families", families.size());
        m.put("largest_family_size", families.values().stream().mapToInt(List::size).max().orElse(0));
        m.put("originals_without_student_key", withoutStudentKey);

        Map<String, Object> counts = new TreeMap<>();
        for (String hw : homeworks) {
            Map<String, Map<String, Integer>> bySplit = new TreeMap<>();
            for (Sub s : subsByHomework.get(hw)) {
                bySplit.computeIfAbsent(splitOf.get(s.id()).name(), k -> new TreeMap<>())
                        .merge(s.kind().name(), 1, Integer::sum);
            }
            counts.put(hw, bySplit);
        }
        m.put("submissions_by_homework_split_kind", counts);
        m.put("pair_labels_by_split", pairCounts);
        m.put("cross_split_pairs_excluded", crossSplit);
        m.put("label_definitions", Map.of(
                "COPY", "one side derived from the other (SOURCE/HOST) or both from a common submission (SIBLING)",
                "NEGATIVE", "exactly one side is a negative control",
                "EXCLUDED", "both sides are negative controls (same author, not independent)",
                "UNRESOLVED", "no verified provenance (e.g. original vs original); not a negative",
                "CROSS_SPLIT", "the two sides are in different splits; leave out of metrics"));
        try {
            Files.createDirectories(config.outDir());
            JSON.writeValue(config.outDir().resolve("split_log.json").toFile(), m);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ utilities

    /** Reads a CSV written by this project (delegates to {@link Csv#read}). */
    static List<Map<String, String>> readCsv(Path file) {
        return Csv.read(file);
    }

    private static final class UnionFind {
        private final Map<String, String> parent = new HashMap<>();

        void add(String x) {
            parent.putIfAbsent(x, x);
        }

        String find(String x) {
            String p = parent.get(x);
            if (!p.equals(x)) {
                p = find(p);
                parent.put(x, p);
            }
            return p;
        }

        void union(String a, String b) {
            String ra = find(a);
            String rb = find(b);
            if (!ra.equals(rb)) {
                // smaller name wins, so the result does not depend on union order
                if (ra.compareTo(rb) < 0) {
                    parent.put(rb, ra);
                } else {
                    parent.put(ra, rb);
                }
            }
        }
    }
}
