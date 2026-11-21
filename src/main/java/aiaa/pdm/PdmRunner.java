package aiaa.pdm;

import aiaa.data.DataLayout;
import aiaa.llm.ModelAdapter;
import aiaa.llm.ModelResponse;
import aiaa.pdm.ComparisonResult.Comparison;
import aiaa.pdm.ComparisonResult.Method;
import aiaa.pdm.ComparisonResult.Status;
import aiaa.pdm.PlagiarismScorer.StudentScore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates one PDM run for one assignment:
 * load + anonymize -> exclude starter material -> token baseline on all comparisons ->
 * LLM on all comparisons (optional) -> student-level scores -> CSV / JSON outputs.
 */
public final class PdmRunner {

    static final String COMPARISONS_CSV = "comparisons.csv";
    static final String SCORES_CSV = "submission_scores.csv";
    static final String INVENTORY_CSV = "inventory.csv";
    static final String MANIFEST_JSON = "run_manifest.json";
    static final String RAW_JSONL = "llm_responses.jsonl";

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final ObjectMapper JSONL = new ObjectMapper();

    private final PdmConfig config;
    private final ModelAdapter adapter;
    /** Submission ID -> cohort label (references are not included). */
    private final Map<String, String> cohortOf = new HashMap<>();

    /** @param adapter LLM adapter, or null to run the token baseline only */
    public PdmRunner(PdmConfig config, ModelAdapter adapter) {
        this.config = config;
        this.adapter = adapter;
    }

    /** One row of inventory.csv: what was loaded and what was excluded, per submission/reference. */
    record InventoryRow(String id, String cohort, String role, String source, List<String> included, List<String> excludedFiles,
                        int redactions, int starterRemoved, int substantiveBefore, int substantiveAfter,
                        boolean eligible, String reason) {
    }

    public record Summary(Path outDir, int eligibleSubmissions, int excludedSubmissions, int references,
                          int peerPairs, int llmCalls, int llmFailures, long inputTokens, long outputTokens,
                          Double costUsd, long wallMs) {
    }

    /** Everything loaded and prepared before any scoring. */
    private record Inputs(StarterCodeFilter filter, List<InventoryRow> inventory, List<SourceView> students,
                          List<SourceView> references, Map<String, String[]> idMap) {
    }

    public Summary run() {
        Instant started = Instant.now();
        String runId = config.assignment() + "-" + config.starterMode().name().toLowerCase(Locale.ROOT) + "-"
                + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(started);
        Inputs in = loadInputs();
        StarterCodeFilter filter = in.filter();
        List<InventoryRow> inventory = in.inventory();
        List<SourceView> students = in.students();
        List<SourceView> references = in.references();
        Map<String, String[]> idMap = in.idMap();

        // Token baseline on every comparison (cheap, so always exhaustive).
        List<ComparisonResult> results = new ArrayList<>();
        int peerPairs = 0;
        for (int i = 0; i < students.size(); i++) {
            for (int j = i + 1; j < students.size(); j++) {
                results.add(token(Comparison.PEER, students.get(i), students.get(j)));
                peerPairs++;
            }
            for (SourceView ref : references) {
                results.add(token(Comparison.AI_REFERENCE, students.get(i), ref));
            }
        }

        LlmStats stats = new LlmStats();
        if (adapter != null) {
            results.addAll(runLlm(students, references, stats));
        }

        List<String> ids = students.stream().map(SourceView::id).toList();
        List<StudentScore> scores = new ArrayList<>(
                PlagiarismScorer.score(ids, results, Method.TOKEN, config.alpha(), cohortOf));
        if (adapter != null) {
            scores.addAll(PlagiarismScorer.score(ids, results, Method.LLM, config.alpha(), cohortOf));
        }
        long crossPairs = results.stream().filter(r -> r.method() == Method.TOKEN && r.comparison() == Comparison.PEER
                && !cohortOf.get(r.subA()).equals(cohortOf.get(r.subB()))).count();

        Instant finished = Instant.now();
        long wallMs = finished.toEpochMilli() - started.toEpochMilli();
        writeComparisons(runId, results);
        writeScores(runId, scores);
        writeInventory(runId, inventory);
        writeIdMap(idMap);
        long excluded = inventory.stream().filter(r -> r.role().equals("student") && !r.eligible()).count();
        writeManifest(runId, started, finished, students.size(), (int) excluded, references.size(), peerPairs,
                (int) crossPairs, stats, filter);

        return new Summary(config.outDir(), students.size(), (int) excluded, references.size(), peerPairs,
                stats.calls.get(), stats.failures.get(), stats.inputTokens(), stats.outputTokens(),
                config.costUsd(stats.inputTokens(), stats.outputTokens()), wallMs);
    }

    private Inputs loadInputs() {
        StarterCodeFilter filter = buildFilter();
        List<InventoryRow> inventory = new ArrayList<>();
        List<SourceView> students = new ArrayList<>();
        Map<String, String[]> idMap = new LinkedHashMap<>(); // anon -> [cohort, folder]
        cohortOf.clear();
        for (DataLayout.Cohort cohort : config.cohorts()) {
            for (SubmissionIds.Entry e : SubmissionIds.assign(cohort, config.idsFromFolders())) {
                if (cohortOf.putIfAbsent(e.id(), cohort.label()) != null) {
                    throw new IllegalArgumentException("Duplicate submission ID " + e.id()
                            + " (two cohorts with the same folder names?)");
                }
                if (e.renamed()) {
                    idMap.put(e.id(), new String[]{cohort.label(), e.folder().getFileName().toString()});
                }
                SourceView v = prepare(e.id(), "student", e.folder(), filter, inventory);
                if (v != null) {
                    students.add(v);
                }
            }
        }
        List<SourceView> references = new ArrayList<>();
        for (Path ref : config.referencePaths()) {
            String name = ref.getFileName().toString().replaceAll("\\.(zip|java)$", "");
            SourceView v = prepare("REF-" + name, "reference", ref, filter, inventory);
            if (v != null) {
                references.add(v);
            }
        }
        return new Inputs(filter, inventory, students, references, idMap);
    }

    /** Result of {@link #estimate}: what an LLM run would send, without calling any model. */
    public record Estimate(int peerCalls, int aiCalls, int tooLarge, long inputTokens, long outputTokens,
                           Double costUsd, int submissions, double inputTokensPerPeerCall,
                           long extrapolatedInputTokens100, long extrapolatedOutputTokens100,
                           Double extrapolatedCostUsd100) {
    }

    /**
     * Dry run: builds every prompt the LLM run would send and estimates tokens and cost, without calling
     * any model. Input tokens are estimated from characters ({@link aiaa.llm.TokenEstimate}); output tokens
     * per call are assumed. Also extrapolates to the spec's benchmark size of 100 submissions (4,950 pairs).
     */
    public Estimate estimate(String modelId, int assumedOutputTokens) {
        Inputs in = loadInputs();
        LlmSimilarityJudge judge = new LlmSimilarityJudge(
                new aiaa.llm.MockAdapter(modelId, r -> ""), config.assignment(), config.starterMode(),
                config.maxOutputTokens(), config.temperature(), config.maxInputChars());
        String system = LlmSimilarityJudge.resource(LlmSimilarityJudge.SYSTEM_RESOURCE);
        List<SourceView> s = in.students();
        long chars = 0;
        long peerChars = 0;
        int peer = 0;
        int ai = 0;
        int tooLarge = 0;
        for (int i = 0; i < s.size(); i++) {
            for (int j = i + 1; j < s.size(); j++) {
                if (config.maxLlmPeerPairs() != null && peer >= config.maxLlmPeerPairs()) {
                    break;
                }
                int c = system.length() + judge.buildUserPrompt(Comparison.PEER, s.get(i), s.get(j)).length();
                if (c > config.maxInputChars()) {
                    tooLarge++;
                    continue;
                }
                chars += c;
                peerChars += c;
                peer++;
            }
            for (SourceView ref : in.references()) {
                int c = system.length() + judge.buildUserPrompt(Comparison.AI_REFERENCE, s.get(i), ref).length();
                if (c > config.maxInputChars()) {
                    tooLarge++;
                    continue;
                }
                chars += c;
                ai++;
            }
        }
        long inTok = aiaa.llm.TokenEstimate.fromChars(chars);
        long outTok = (long) (peer + ai) * assumedOutputTokens;
        double perPeer = peer == 0 ? 0 : (double) aiaa.llm.TokenEstimate.fromChars(peerChars) / peer;
        long in100 = Math.round(perPeer * 4950);
        long out100 = 4950L * assumedOutputTokens;
        return new Estimate(peer, ai, tooLarge, inTok, outTok, config.costUsd(inTok, outTok), s.size(), perPeer,
                in100, out100, config.costUsd(in100, out100));
    }

    private StarterCodeFilter buildFilter() {
        if (config.starterMode() != StarterMode.STRIP || config.starterPaths().isEmpty()) {
            return null;
        }
        List<String> sources = new ArrayList<>();
        for (Path p : config.starterPaths()) {
            sources.addAll(SourceFiles.readJava(p).values());
        }
        return StarterCodeFilter.fromSources(sources);
    }

    private SourceView prepare(String id, String role, Path source, StarterCodeFilter filter, List<InventoryRow> inventory) {
        Map<String, String> raw = SourceFiles.readJava(source);
        if (raw.isEmpty()) {
            inventory.add(new InventoryRow(id, cohortOf.getOrDefault(id, ""), role, source.getFileName().toString(), List.of(), List.of(),
                    0, 0, 0, 0, false, "no .java files"));
            return null;
        }
        Map<String, String> files = new LinkedHashMap<>();
        List<String> excludedFiles = new ArrayList<>();
        int redactions = 0;
        for (Map.Entry<String, String> e : SourceFiles.withDisplayNames(raw).entrySet()) {
            if (config.starterMode() == StarterMode.STRIP && isExcluded(e.getKey())) {
                excludedFiles.add(e.getKey());
                continue;
            }
            SourceFiles.Scrubbed s = SourceFiles.scrubIdentity(e.getValue());
            redactions += s.redactions();
            files.put(e.getKey(), s.text());
        }
        SourceView view = SourceView.of(id, files);
        int before = view.substantiveLineCount();
        int removed = 0;
        if (filter != null) {
            StarterCodeFilter.Result r = filter.filter(view);
            view = r.view();
            removed = r.removedLines();
        }
        int after = view.substantiveLineCount();
        boolean eligible = after > 0;
        String reason = eligible ? "" : (files.isEmpty() ? "only excluded files" : "no code remaining after starter exclusion");
        // Folder names stay out of the public inventory for students (they contain student IDs).
        String shownSource = role.equals("student") ? "" : source.getFileName().toString();
        inventory.add(new InventoryRow(id, cohortOf.getOrDefault(id, ""), role, shownSource, List.copyOf(files.keySet()), excludedFiles,
                redactions, removed, before, after, eligible, reason));
        return eligible ? view : null;
    }

    private boolean isExcluded(String displayName) {
        return config.excludedFiles().stream().anyMatch(x -> x.equalsIgnoreCase(displayName));
    }

    private ComparisonResult token(Comparison c, SourceView a, SourceView b) {
        TokenSimilarity.Score s = TokenSimilarity.compare(a.plainText(), b.plainText(), config.tokenK());
        return new ComparisonResult(c, Method.TOKEN, a.id(), b.id(), s.jaccard(), null, null, null, null,
                s.containment(), null, null, Status.OK, null, "none", "token-kgram-k" + config.tokenK(), null,
                null, 0, 0, 0, 0, null);
    }

    // ---------------------------------------------------------------- LLM

    private static final class LlmStats {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger resumed = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger retries = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicLong in = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong out = new java.util.concurrent.atomic.AtomicLong();
        final Map<Status, AtomicInteger> byStatus = new EnumMap<>(Status.class);
        int peerPairsPlanned;
        int aiPlanned;

        LlmStats() {
            for (Status s : Status.values()) {
                byStatus.put(s, new AtomicInteger());
            }
        }

        long inputTokens() {
            return in.get();
        }

        long outputTokens() {
            return out.get();
        }
    }

    private record Task(Comparison comparison, SourceView a, SourceView b) {
    }

    private List<ComparisonResult> runLlm(List<SourceView> students, List<SourceView> references, LlmStats stats) {
        LlmSimilarityJudge judge = new LlmSimilarityJudge(adapter, config.assignment(), config.starterMode(),
                config.maxOutputTokens(), config.temperature(), config.maxInputChars());

        List<Task> tasks = new ArrayList<>();
        int peer = 0;
        for (int i = 0; i < students.size(); i++) {
            for (int j = i + 1; j < students.size(); j++) {
                if (config.maxLlmPeerPairs() == null || peer < config.maxLlmPeerPairs()) {
                    tasks.add(new Task(Comparison.PEER, students.get(i), students.get(j)));
                    peer++;
                }
            }
        }
        for (SourceView s : students) {
            for (SourceView ref : references) {
                tasks.add(new Task(Comparison.AI_REFERENCE, s, ref));
            }
        }
        stats.peerPairsPlanned = peer;
        stats.aiPlanned = tasks.size() - peer;

        Path rawFile = config.outDir().resolve(RAW_JSONL);
        Map<String, ComparisonResult> previous = config.resume() ? loadPrevious(rawFile, judge) : Map.of();

        List<ComparisonResult> results = new ArrayList<>();
        try {
            Files.createDirectories(config.outDir());
            List<String> keptLines = config.resume() && Files.exists(rawFile)
                    ? Files.readAllLines(rawFile, StandardCharsets.UTF_8) : List.of();
            try (BufferedWriter raw = Files.newBufferedWriter(rawFile, StandardCharsets.UTF_8)) {
                for (String line : keptLines) {
                    raw.write(line);
                    raw.newLine();
                }
                ExecutorService pool = Executors.newFixedThreadPool(config.parallelism());
                try {
                    List<Future<ComparisonResult>> futures = new ArrayList<>();
                    AtomicInteger done = new AtomicInteger();
                    for (Task t : tasks) {
                        ComparisonResult prior = previous.get(ComparisonResult.key(t.comparison(), t.a().id(), t.b().id()));
                        if (prior != null) {
                            stats.resumed.incrementAndGet();
                            results.add(prior);
                            continue;
                        }
                        futures.add(pool.submit(() -> {
                            ComparisonResult r = judge.judge(t.comparison(), t.a(), t.b());
                            tally(stats, r);
                            synchronized (raw) {
                                raw.write(rawLine(r));
                                raw.newLine();
                                raw.flush();
                            }
                            int n = done.incrementAndGet();
                            if (n % 10 == 0 || n == tasks.size()) {
                                System.err.printf("  LLM %d/%d done%n", n, tasks.size() - stats.resumed.get());
                            }
                            return r;
                        }));
                    }
                    for (Future<ComparisonResult> f : futures) {
                        results.add(f.get());
                    }
                } finally {
                    pool.shutdown();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("LLM task failed", e.getCause());
        }
        return results;
    }

    private static void tally(LlmStats stats, ComparisonResult r) {
        if (r.status() != Status.TOO_LARGE) {
            stats.calls.incrementAndGet();
        }
        if (!r.ok()) {
            stats.failures.incrementAndGet();
        }
        stats.byStatus.get(r.status()).incrementAndGet();
        stats.retries.addAndGet(r.retries());
        stats.in.addAndGet(r.inputTokens());
        stats.out.addAndGet(r.outputTokens());
    }

    private String rawLine(ComparisonResult r) {
        ObjectNode n = JSONL.createObjectNode();
        n.put("key", r.key());
        n.put("timestamp", Instant.now().toString());
        n.put("comparison", r.comparison().name());
        n.put("sub_a", r.subA());
        n.put("sub_b", r.subB());
        n.put("starter_mode", config.starterMode().name());
        n.put("model_provider", r.modelProvider());
        n.put("model_id", r.modelId());
        n.put("model_returned", r.modelReturned());
        n.put("prompt_version", r.promptVersion());
        n.put("status", r.status().name());
        n.put("error", r.error());
        n.put("input_tokens", r.inputTokens());
        n.put("output_tokens", r.outputTokens());
        n.put("latency_ms", r.latencyMs());
        n.put("retries", r.retries());
        n.put("response", r.rawResponse());
        return n.toString();
    }

    /** Successful saved responses with the same model, prompt version and starter mode. */
    private Map<String, ComparisonResult> loadPrevious(Path rawFile, LlmSimilarityJudge judge) {
        Map<String, ComparisonResult> out = new HashMap<>();
        if (!Files.exists(rawFile)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(rawFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode n = JSONL.readTree(line);
                Comparison c = Comparison.valueOf(n.path("comparison").asText());
                if (!"OK".equals(n.path("status").asText())
                        || !adapter.modelId().equals(n.path("model_id").asText())
                        || !judge.promptVersion(c).equals(n.path("prompt_version").asText())
                        || !config.starterMode().name().equals(n.path("starter_mode").asText())) {
                    continue;
                }
                ModelResponse resp = new ModelResponse(n.path("response").asText(), n.path("model_id").asText(),
                        n.path("model_returned").asText(), n.path("input_tokens").asLong(),
                        n.path("output_tokens").asLong(), n.path("latency_ms").asLong(), n.path("retries").asInt());
                ComparisonResult r = judge.fromResponse(c, n.path("sub_a").asText(), n.path("sub_b").asText(),
                        n.path("prompt_version").asText(), resp);
                if (r.ok()) {
                    out.put(r.key(), r);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + rawFile, e);
        }
        return out;
    }

    // ---------------------------------------------------------------- outputs

    private void writeComparisons(String runId, List<ComparisonResult> results) {
        List<String> header = List.of("run_id", "assignment", "starter_mode", "comparison", "method", "sub_a", "sub_b",
                "cohort_a", "cohort_b", "cohort_relation", "similarity", "containment", "structure", "syntax", "tokens", "logic", "status", "error",
                "evidence", "rationale", "model_provider", "model_id", "model_returned", "prompt_version",
                "input_tokens", "output_tokens", "latency_ms", "retries", "cost_usd");
        List<List<Object>> rows = new ArrayList<>();
        for (ComparisonResult r : results) {
            List<Object> row = new ArrayList<>(List.of(runId, config.assignment(), config.starterMode().name(),
                    r.comparison().name(), r.method().name(), r.subA(), r.subB()));
            String ca = cohortOf.getOrDefault(r.subA(), "");
            String cb = cohortOf.getOrDefault(r.subB(), "");
            row.add(ca);
            row.add(cb);
            row.add(r.comparison() == Comparison.AI_REFERENCE ? "REFERENCE" : (ca.equals(cb) ? "WITHIN" : "CROSS"));
            row.add(r.similarity());
            row.add(r.containment());
            row.add(r.structure());
            row.add(r.syntax());
            row.add(r.tokens());
            row.add(r.logic());
            row.add(r.status().name());
            row.add(r.error());
            row.add(r.evidence());
            row.add(r.rationale());
            row.add(r.modelProvider());
            row.add(r.modelId());
            row.add(r.modelReturned());
            row.add(r.promptVersion());
            row.add(r.inputTokens());
            row.add(r.outputTokens());
            row.add(r.latencyMs());
            row.add(r.retries());
            row.add(r.method() == Method.LLM ? config.costUsd(r.inputTokens(), r.outputTokens()) : null);
            rows.add(row);
        }
        Csv.write(config.outDir().resolve(COMPARISONS_CSV), header, rows);
    }

    private void writeScores(String runId, List<StudentScore> scores) {
        List<String> header = List.of("run_id", "assignment", "starter_mode", "method", "submission", "cohort",
                "max_peer_similarity", "max_peer_partner", "max_peer_within_cohort", "max_peer_cross_cohort",
                "peer_ok", "peer_failed",
                "ai_similarity", "ai_reference", "ai_ok", "ai_failed", "alpha", "combined_score");
        List<List<Object>> rows = new ArrayList<>();
        for (StudentScore s : scores) {
            List<Object> row = new ArrayList<>();
            row.add(runId);
            row.add(config.assignment());
            row.add(config.starterMode().name());
            row.add(s.method().name());
            row.add(s.submission());
            row.add(cohortOf.get(s.submission()));
            row.add(s.maxPeer());
            row.add(s.maxPeerPartner());
            row.add(s.maxPeerWithin());
            row.add(s.maxPeerCross());
            row.add(s.peerOk());
            row.add(s.peerFailed());
            row.add(s.aiSimilarity());
            row.add(s.aiReference());
            row.add(s.aiOk());
            row.add(s.aiFailed());
            row.add(s.alpha());
            row.add(s.combined());
            rows.add(row);
        }
        Csv.write(config.outDir().resolve(SCORES_CSV), header, rows);
    }

    private void writeInventory(String runId, List<InventoryRow> inventory) {
        List<String> header = List.of("run_id", "assignment", "starter_mode", "id", "cohort", "role", "source",
                "files_included", "files_excluded", "identity_redactions", "starter_lines_removed",
                "substantive_lines_before", "substantive_lines_after", "eligible", "exclusion_reason");
        List<List<Object>> rows = new ArrayList<>();
        for (InventoryRow r : inventory) {
            rows.add(List.of(runId, config.assignment(), config.starterMode().name(), r.id(), r.cohort(), r.role(), r.source(),
                    String.join(";", r.included()), String.join(";", r.excludedFiles()), r.redactions(),
                    r.starterRemoved(), r.substantiveBefore(), r.substantiveAfter(), r.eligible(), r.reason()));
        }
        Csv.write(config.outDir().resolve(INVENTORY_CSV), header, rows);
    }

    private void writeIdMap(Map<String, String[]> idMap) {
        if (config.idMapFile() == null || idMap.isEmpty()) {
            return;
        }
        List<List<Object>> rows = new ArrayList<>();
        idMap.forEach((k, v) -> rows.add(List.of(k, v[0], v[1])));
        Csv.write(config.idMapFile(), List.of("anon_id", "cohort", "source_folder"), rows);
    }

    private void writeManifest(String runId, Instant started, Instant finished, int eligible, int excluded,
                               int references, int peerPairs, int crossPairs, LlmStats stats,
                               StarterCodeFilter filter) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", runId);
        m.put("module", "PDM");
        m.put("assignment", config.assignment());
        m.put("started_utc", started.toString());
        m.put("finished_utc", finished.toString());
        m.put("wall_clock_ms", finished.toEpochMilli() - started.toEpochMilli());
        m.put("java_version", System.getProperty("java.version"));

        Map<String, Object> input = new LinkedHashMap<>();
        List<Map<String, String>> cohorts = new ArrayList<>();
        for (DataLayout.Cohort c : config.cohorts()) {
            cohorts.add(Map.of("label", c.label(), "dir", c.dir().toString()));
        }
        input.put("cohorts", cohorts);
        input.put("starter_paths", config.starterPaths().stream().map(Path::toString).toList());
        input.put("starter_mode", config.starterMode().name());
        input.put("starter_substantive_lines", filter == null ? 0 : filter.starterLineCount());
        input.put("excluded_files", config.excludedFiles());
        input.put("reference_paths", config.referencePaths().stream().map(Path::toString).toList());
        m.put("input", input);

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("eligible_submissions", eligible);
        counts.put("excluded_submissions", excluded);
        counts.put("references", references);
        counts.put("peer_pairs", peerPairs);
        counts.put("peer_pairs_within_cohort", peerPairs - crossPairs);
        counts.put("peer_pairs_cross_cohort", crossPairs);
        counts.put("ai_reference_comparisons", eligible * references);
        m.put("counts", counts);

        Map<String, Object> scoring = new LinkedHashMap<>();
        scoring.put("alpha", config.alpha());
        scoring.put("formula", "S_i = alpha * max_j(S_ij) + (1 - alpha) * max_ref(S_i_AI)");
        scoring.put("token_baseline", "normalized token k-gram Jaccard (containment also reported), k=" + config.tokenK());
        m.put("scoring", scoring);

        Map<String, Object> llm = new LinkedHashMap<>();
        if (adapter == null) {
            llm.put("enabled", false);
        } else {
            LlmSimilarityJudge judge = new LlmSimilarityJudge(adapter, config.assignment(), config.starterMode(),
                    config.maxOutputTokens(), config.temperature(), config.maxInputChars());
            llm.put("enabled", true);
            llm.put("provider", adapter.provider());
            llm.put("model_id", adapter.modelId());
            llm.put("effort", (adapter instanceof aiaa.llm.AnthropicAdapter aa ? (aa.effort() == null ? "model default" : aa.effort()) : "n/a"));
            llm.put("prompt_version_peer", judge.promptVersion(Comparison.PEER));
            llm.put("prompt_version_ai_reference", judge.promptVersion(Comparison.AI_REFERENCE));
            llm.put("temperature", config.temperature() == null ? "provider default (not sent)" : config.temperature());
            llm.put("max_output_tokens", config.maxOutputTokens());
            llm.put("max_input_chars", config.maxInputChars());
            llm.put("max_retries", config.maxRetries());
            llm.put("parallelism", config.parallelism());
            llm.put("peer_pairs_sent", stats.peerPairsPlanned);
            llm.put("peer_pairs_total", peerPairs);
            llm.put("peer_pair_limit", config.maxLlmPeerPairs());
            llm.put("ai_reference_comparisons_sent", stats.aiPlanned);
            llm.put("api_calls", stats.calls.get());
            llm.put("resumed_from_saved_responses", stats.resumed.get());
            Map<String, Integer> byStatus = new LinkedHashMap<>();
            stats.byStatus.forEach((k, v) -> byStatus.put(k.name(), v.get()));
            llm.put("results_by_status", byStatus);
            llm.put("retries", stats.retries.get());
            llm.put("input_tokens", stats.inputTokens());
            llm.put("output_tokens", stats.outputTokens());
            llm.put("price_input_usd_per_mtok", config.priceInputPerMTok());
            llm.put("price_output_usd_per_mtok", config.priceOutputPerMTok());
            llm.put("pricing_source", config.pricingSource());
            llm.put("estimated_cost_usd", config.costUsd(stats.inputTokens(), stats.outputTokens()));
            llm.put("note", "Token/cost totals cover calls made in this invocation, not resumed responses.");
        }
        m.put("llm", llm);
        try {
            Files.createDirectories(config.outDir());
            JSON.writeValue(config.outDir().resolve(MANIFEST_JSON).toFile(), m);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
