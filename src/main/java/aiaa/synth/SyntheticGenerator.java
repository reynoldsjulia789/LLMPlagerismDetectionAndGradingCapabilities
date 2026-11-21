package aiaa.synth;

import aiaa.llm.ModelAdapter;
import aiaa.pdm.Csv;
import aiaa.pdm.JavaLexer;
import aiaa.pdm.SourceFiles;
import aiaa.pdm.SourceView;
import aiaa.pdm.StarterCodeFilter;
import aiaa.pdm.TokenSimilarity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Builds a labeled synthetic-copy dataset for one cohort of one homework. The output folder is
 * itself a cohort folder in the shared layout ({@code <root>/HomeworkSubmissions/<Hw>/<cohort>/}),
 * so the generated root can be screened like the real data:
 * <ul>
 *   <li>copies the originals (identity-scrubbed, anonymous IDs, display file names) to
 *       {@code <assignment>-Sxx/}</li>
 *   <li>derives variants from {@code sourceCount} randomly chosen compiling submissions (and from
 *       each AI reference): exact, renamed, reformatted, comment-edited, reordered, equivalent control
 *       flow (LLM), stacked, and partial (method splice at 25/50/75%)</li>
 *   <li>keeps a variant only if it compiles; records provenance in {@code manifest.csv}</li>
 * </ul>
 * Screen the result with {@code PdmMain --data-root synthetic-data --homework <Hw>}.
 */
public final class SyntheticGenerator {

    public enum Transformation { EXACT, RENAME, REFORMAT, COMMENT_EDIT, REORDER, CONTROL_FLOW, STACKED, PARTIAL }

    public enum Status { OK, FAILED, SKIPPED }

    static final String MANIFEST_CSV = "manifest.csv";
    static final String LOG_JSON = "generation_log.json";
    static final String GENERATOR_VERSION = "synth-v1";
    private static final double[] PARTIAL_LEVELS = {0.25, 0.50, 0.75};
    private static final int MAX_PARTIAL_HOSTS = 8;

    private final SynthConfig config;
    private final ModelAdapter adapter;
    private final Set<String> excluded;
    private StarterCodeFilter filter;
    private final Set<String> sourceIds = new java.util.HashSet<>();
    private final Set<String> usedHosts = new java.util.HashSet<>();

    /** @param adapter LLM used for CONTROL_FLOW variants, or null to skip them */
    public SyntheticGenerator(SynthConfig config, ModelAdapter adapter) {
        this.config = config;
        this.adapter = adapter;
        this.excluded = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        this.excluded.addAll(config.excludedFiles());
    }

    record Program(String id, String kind, Map<String, String> files, boolean compiles, String diagnostics) {
    }

    public record Variant(String id, String sourceKind, String sourceId, String hostId, Transformation transformation,
                          String details, Double targetAmount, Double amountCopied, String methodsCopied,
                          long seed, boolean sourceCompiles, Boolean variantCompiles, Double jaccardVsSource,
                          Double jaccardVsHost, String llmModel, String promptVersion, int llmAttempts,
                          long llmInputTokens, long llmOutputTokens, Status status, String note) {
    }

    public record Summary(Path outDir, int originals, int originalsCompiling, List<Variant> variants) {
        public long ok() {
            return variants.stream().filter(v -> v.status() == Status.OK).count();
        }
    }

    public Summary run() {
        Instant started = Instant.now();
        prepareOutDir();
        filter = buildFilter();

        Map<String, String> idMap = new LinkedHashMap<>();
        List<Program> originals = loadOriginals(idMap, true);
        List<Program> references = loadReferences();
        List<Program> compiling = originals.stream().filter(Program::compiles).toList();
        List<Program> sources = chooseSources(compiling, references);
        sourceIds.clear();
        usedHosts.clear();
        sources.forEach(p -> sourceIds.add(p.id()));

        ControlFlowRewriter rewriter = adapter == null ? null
                : new ControlFlowRewriter(adapter, config.maxOutputTokens(), config.temperature(), config.maxLlmAttempts());

        List<Variant> variants = new ArrayList<>();
        int nextId = 1;
        for (int si = 0; si < sources.size(); si++) {
            Program source = sources.get(si);
            for (Transformation type : Transformation.values()) {
                long seed = variantSeed(source.id(), type);
                Double target = type == Transformation.PARTIAL ? PARTIAL_LEVELS[si % PARTIAL_LEVELS.length] : null;
                System.err.printf("  %s %s%s%n", source.id(), type, target == null ? "" : " " + target);
                Draft d = produce(type, source, compiling, target, new Random(seed), rewriter);
                String id = null;
                Double jSource = null;
                Double jHost = null;
                if (d.status() == Status.OK) {
                    id = String.format("%s-V%02d", config.assignment(), nextId++);
                    write(id, d.files());
                    jSource = jaccard(d.files(), source.files());
                    jHost = d.host() == null ? null : jaccard(d.files(), d.host().files());
                }
                variants.add(new Variant(id, source.kind(), source.id(), d.host() == null ? null : d.host().id(), type,
                        d.details(), target, d.status() == Status.OK ? d.amount() : null, d.methods(), seed,
                        source.compiles(), d.status() == Status.OK ? Boolean.TRUE : d.compiled(), jSource, jHost,
                        d.llm() ? rewriter.modelId() : null, d.llm() ? rewriter.promptVersion() : null,
                        d.llmAttempts(), d.in(), d.out(), d.status(), d.note()));
            }
        }

        writeManifest(variants);
        writeIdMap(idMap);
        writeLog(started, originals, references, sources, variants, rewriter);
        return new Summary(config.outDir(), originals.size(), compiling.size(), variants);
    }

    private List<Program> loadOriginals(Map<String, String> idMap, boolean writeOut) {
        List<Program> originals = new ArrayList<>();
        List<Path> folders = SourceFiles.submissionFolders(config.submissionsDir());
        for (int i = 0; i < folders.size(); i++) {
            String id = String.format("%s-S%02d", config.assignment(), i + 1);
            idMap.put(id, folders.get(i).getFileName().toString());
            Map<String, String> files = load(folders.get(i));
            if (files.isEmpty()) {
                continue;
            }
            CompileCheck.Result c = CompileCheck.compile(files);
            originals.add(new Program(id, "student", files, c.ok(), c.diagnostics()));
            if (writeOut) {
                write(id, files);
            }
        }
        return originals;
    }

    private List<Program> loadReferences() {
        List<Program> references = new ArrayList<>();
        for (Path ref : config.referencePaths()) {
            String id = "REF-" + ref.getFileName().toString().replaceAll("\\.(zip|java)$", "");
            Map<String, String> files = load(ref);
            CompileCheck.Result c = CompileCheck.compile(files);
            references.add(new Program(id, "ai_reference", files, c.ok(), c.diagnostics()));
        }
        return references;
    }

    /** The seeded choice of source submissions, plus every compiling reference. */
    private List<Program> chooseSources(List<Program> compiling, List<Program> references) {
        Random master = new Random(config.seed());
        List<Program> shuffled = new ArrayList<>(compiling);
        Collections.shuffle(shuffled, master);
        List<Program> sources = new ArrayList<>(shuffled.subList(0, Math.min(config.sourceCount(), shuffled.size())));
        references.stream().filter(Program::compiles).forEach(sources::add);
        return sources;
    }

    /** Dry-run estimate for the CONTROL_FLOW variants (token counts are estimates). */
    public record ControlFlowEstimate(int sources, int calls, int maxCallsWithRetries, long inputTokens,
                                      long outputTokens) {
    }

    /** Builds the CONTROL_FLOW prompts this configuration would send, without calling any model or writing data. */
    public ControlFlowEstimate estimateControlFlow() {
        List<Program> compiling = loadOriginals(new LinkedHashMap<>(), false).stream().filter(Program::compiles).toList();
        List<Program> sources = chooseSources(compiling, loadReferences());
        long in = 0;
        long out = 0;
        for (Program p : sources) {
            Map<String, String> targets = new LinkedHashMap<>();
            Map<String, String> fixed = new LinkedHashMap<>();
            p.files().forEach((k, v) -> (excluded.contains(k) ? fixed : targets).put(k, v));
            long[] e = ControlFlowRewriter.estimate(targets, fixed);
            in += e[0];
            out += e[1];
        }
        return new ControlFlowEstimate(sources.size(), sources.size(), sources.size() * config.maxLlmAttempts(), in, out);
    }

    // ------------------------------------------------------------------ transformations

    private record Draft(Status status, Map<String, String> files, String details, double amount, String methods,
                         Program host, Boolean compiled, boolean llm, int llmAttempts, long in, long out, String note) {
        static Draft ok(Map<String, String> files, String details) {
            return new Draft(Status.OK, files, details, 1.0, null, null, true, false, 0, 0, 0, null);
        }

        static Draft failed(String note) {
            return new Draft(Status.FAILED, Map.of(), null, 0, null, null, false, false, 0, 0, 0, note);
        }
    }

    private Draft produce(Transformation type, Program source, List<Program> compiling, Double target, Random rnd,
                          ControlFlowRewriter rewriter) {
        Map<String, String> src = source.files();
        return switch (type) {
            case EXACT -> checked(src, "identical copy of all files");
            case RENAME -> {
                Optional<Draft> d = rename(src, rnd);
                yield d.orElseGet(() -> Draft.failed("no rename compiled"));
            }
            case REFORMAT -> {
                Reformatter.Style style = Reformatter.randomStyle(rnd);
                Map<String, String> out = new LinkedHashMap<>();
                src.forEach((k, v) -> out.put(k, Reformatter.format(v, style)));
                if (!sameCodeTokens(out, src)) {
                    yield Draft.failed("reformat changed code tokens (bug)");
                }
                yield checked(out, style.describe());
            }
            case COMMENT_EDIT -> {
                CommentEditor.Result r = CommentEditor.edit(src, rnd);
                yield r.changed()
                        ? checked(r.files(), String.format("%d deleted, %d reworded, %d added", r.deleted(), r.reworded(), r.added()))
                        : Draft.failed("no comments to edit and none added");
            }
            case REORDER -> {
                MethodReorderer.Result r = MethodReorderer.reorder(src, rnd);
                yield r.changed() ? checked(r.files(), String.join("; ", r.changes()))
                        : Draft.failed("fewer than two movable members");
            }
            case STACKED -> stacked(src, rnd);
            case CONTROL_FLOW -> controlFlow(src, rewriter);
            case PARTIAL -> partial(source, compiling, target, rnd);
        };
    }

    private Draft checked(Map<String, String> files, String details) {
        CompileCheck.Result c = CompileCheck.compile(files);
        return c.ok() ? Draft.ok(files, details) : new Draft(Status.FAILED, Map.of(), details, 0, null, null, false,
                false, 0, 0, 0, "does not compile: " + firstLine(c.diagnostics()));
    }

    private Optional<Draft> rename(Map<String, String> src, Random rnd) {
        long seed = rnd.nextLong();
        for (Renamer.Scope scope : Renamer.Scope.values()) {
            Renamer.Result r = Renamer.rename(src, scope, new Random(seed));
            if (r.renames().isEmpty()) {
                continue;
            }
            if (CompileCheck.compiles(r.files())) {
                return Optional.of(Draft.ok(r.files(), scope.name().toLowerCase(Locale.ROOT) + ": "
                        + r.renames().size() + " identifiers " + abbreviate(r.renames().toString(), 300)));
            }
        }
        return Optional.empty();
    }

    private Draft stacked(Map<String, String> src, Random rnd) {
        long seed = rnd.nextLong();
        for (Renamer.Scope scope : Renamer.Scope.values()) {
            Random r = new Random(seed);
            Renamer.Result renamed = Renamer.rename(src, scope, r);
            MethodReorderer.Result reordered = MethodReorderer.reorder(renamed.files(), r);
            CommentEditor.Result commented = CommentEditor.edit(reordered.files(), r);
            Reformatter.Style style = Reformatter.randomStyle(r);
            Map<String, String> out = new LinkedHashMap<>();
            commented.files().forEach((k, v) -> out.put(k, Reformatter.format(v, style)));
            if (CompileCheck.compiles(out)) {
                return Draft.ok(out, String.format("rename(%s, %d) + reorder(%d classes) + comments(%d/%d/%d) + %s",
                        scope.name().toLowerCase(Locale.ROOT), renamed.renames().size(), reordered.changes().size(),
                        commented.deleted(), commented.reworded(), commented.added(), style.describe()));
            }
        }
        return Draft.failed("no stacked combination compiled");
    }

    private Draft controlFlow(Map<String, String> src, ControlFlowRewriter rewriter) {
        if (rewriter == null) {
            return new Draft(Status.SKIPPED, Map.of(), null, 0, null, null, null, false, 0, 0, 0,
                    "no LLM adapter configured");
        }
        Map<String, String> targets = new LinkedHashMap<>();
        Map<String, String> fixed = new LinkedHashMap<>();
        src.forEach((k, v) -> (excluded.contains(k) ? fixed : targets).put(k, v));
        ControlFlowRewriter.Outcome o = rewriter.rewrite(targets, fixed);
        if (!o.ok()) {
            return new Draft(Status.FAILED, Map.of(), null, 0, null, null, false, true, o.attempts(),
                    o.inputTokens(), o.outputTokens(), firstLine(o.error()));
        }
        Map<String, String> all = new LinkedHashMap<>(src);
        all.putAll(o.files());
        return new Draft(Status.OK, all, abbreviate(o.changes(), 500), 1.0, null, null, true, true, o.attempts(),
                o.inputTokens(), o.outputTokens(), null);
    }

    /**
     * Hosts are taken from submissions that are neither a source nor already a host. Otherwise
     * source/host links would chain many students into one split family (A's partial copy lives in
     * B, B's in C, ...). Falls back to any other compiling submission only when none is left.
     */
    private Draft partial(Program source, List<Program> compiling, double target, Random rnd) {
        List<Program> fresh = new ArrayList<>(compiling.stream()
                .filter(p -> !sourceIds.contains(p.id()) && !usedHosts.contains(p.id())).toList());
        List<Program> others = new ArrayList<>(compiling.stream()
                .filter(p -> !p.id().equals(source.id()) && !fresh.contains(p)).toList());
        Collections.shuffle(fresh, rnd);
        Collections.shuffle(others, rnd);
        List<Program> hosts = new ArrayList<>(fresh);
        hosts.addAll(others);
        for (Program host : hosts.subList(0, Math.min(MAX_PARTIAL_HOSTS, hosts.size()))) {
            Optional<MethodSplicer.Result> r = MethodSplicer.splice(source.files(), host.files(), target, filter,
                    excluded, new Random(rnd.nextLong()), CompileCheck::compiles);
            if (r.isPresent()) {
                MethodSplicer.Result s = r.get();
                usedHosts.add(host.id());
                return new Draft(Status.OK, s.files(), String.format(Locale.ROOT,
                        "%d of host's methods replaced by source's (target %.0f%%, achieved %.1f%%)",
                        s.methods().size(), target * 100, s.fraction() * 100), s.fraction(),
                        String.join(";", s.methods()), host, true, false, 0, 0, 0, null);
            }
        }
        return Draft.failed("no compiling method subset within " + Math.round(MethodSplicer.TOLERANCE * 100)
                + " points of target");
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, String> load(Path path) {
        Map<String, String> out = new LinkedHashMap<>();
        SourceFiles.withDisplayNames(SourceFiles.readJava(path))
                .forEach((k, v) -> out.put(k, SourceFiles.scrubIdentity(v).text()));
        return out;
    }

    private StarterCodeFilter buildFilter() {
        if (config.starterPaths().isEmpty()) {
            return null;
        }
        List<String> sources = new ArrayList<>();
        config.starterPaths().forEach(p -> sources.addAll(SourceFiles.readJava(p).values()));
        return StarterCodeFilter.fromSources(sources);
    }

    /** Token Jaccard on student-written code (starter lines and excluded files removed). */
    private double jaccard(Map<String, String> a, Map<String, String> b) {
        return TokenSimilarity.compare(ownCode(a), ownCode(b)).jaccard();
    }

    private String ownCode(Map<String, String> files) {
        Map<String, String> kept = new TreeMap<>();
        files.forEach((k, v) -> {
            if (!excluded.contains(k)) {
                kept.put(k, v);
            }
        });
        SourceView v = SourceView.of("x", kept);
        return (filter == null ? v : filter.filter(v).view()).plainText();
    }

    private static boolean sameCodeTokens(Map<String, String> a, Map<String, String> b) {
        for (String k : b.keySet()) {
            List<String> x = JavaLexer.significant(JavaLexer.lex(a.get(k))).stream().map(JavaLexer.Token::text).toList();
            List<String> y = JavaLexer.significant(JavaLexer.lex(b.get(k))).stream().map(JavaLexer.Token::text).toList();
            if (!x.equals(y)) {
                return false;
            }
        }
        return true;
    }

    private long variantSeed(String sourceId, Transformation type) {
        long h = config.seed();
        for (char c : (sourceId + "|" + type).toCharArray()) {
            h = h * 1_000_003L + c;
        }
        return h;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        int nl = t.indexOf('\n');
        return abbreviate(nl < 0 ? t : t.substring(0, nl), 200);
    }

    private static String abbreviate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ------------------------------------------------------------------ output

    /** Refuses to write into a non-empty folder that this generator did not create. */
    private void prepareOutDir() {
        Path out = config.outDir();
        try {
            if (Files.isDirectory(out)) {
                boolean empty;
                try (Stream<Path> s = Files.list(out)) {
                    empty = s.findAny().isEmpty();
                }
                if (!empty && !Files.exists(out.resolve(LOG_JSON))) {
                    throw new IllegalArgumentException("Output folder " + out + " is not empty and was not created by "
                            + "this generator; choose another --out");
                }
                // Regenerating: remove only the submission folders this generator wrote (<assignment>-Sxx / -Vxx).
                String prefix = config.assignment() + "-";
                try (Stream<Path> s = Files.list(out)) {
                    for (Path dir : s.filter(Files::isDirectory).toList()) {
                        String name = dir.getFileName().toString();
                        if (name.startsWith(prefix) && name.substring(prefix.length()).matches("[SV]\\d+")) {
                            try (Stream<Path> walk = Files.walk(dir)) {
                                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                            }
                        }
                    }
                }
            }
            Files.createDirectories(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(String id, Map<String, String> files) {
        Path dir = config.outDir().resolve(id);
        try {
            Files.createDirectories(dir);
            for (Map.Entry<String, String> e : files.entrySet()) {
                Files.writeString(dir.resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeManifest(List<Variant> variants) {
        List<String> header = List.of("variant_id", "assignment", "source_kind", "source_id", "host_id",
                "transformation", "details", "target_amount", "amount_copied", "methods_copied", "seed",
                "source_compiles", "variant_compiles", "token_jaccard_vs_source", "token_jaccard_vs_host",
                "llm_model", "prompt_version", "llm_attempts", "llm_input_tokens", "llm_output_tokens",
                "generator_version", "status", "note");
        List<List<Object>> rows = new ArrayList<>();
        for (Variant v : variants) {
            List<Object> row = new ArrayList<>();
            row.add(v.id());
            row.add(config.assignment());
            row.add(v.sourceKind());
            row.add(v.sourceId());
            row.add(v.hostId());
            row.add(v.transformation());
            row.add(v.details());
            row.add(v.targetAmount());
            row.add(v.amountCopied());
            row.add(v.methodsCopied());
            row.add(v.seed());
            row.add(v.sourceCompiles());
            row.add(v.variantCompiles());
            row.add(v.jaccardVsSource());
            row.add(v.jaccardVsHost());
            row.add(v.llmModel());
            row.add(v.promptVersion());
            row.add(v.llmAttempts());
            row.add(v.llmInputTokens());
            row.add(v.llmOutputTokens());
            row.add(GENERATOR_VERSION);
            row.add(v.status());
            row.add(v.note());
            rows.add(row);
        }
        Csv.write(config.outDir().resolve(MANIFEST_CSV), header, rows);
    }

    private void writeIdMap(Map<String, String> idMap) {
        if (config.idMapFile() == null) {
            return;
        }
        List<List<Object>> rows = new ArrayList<>();
        idMap.forEach((k, v) -> rows.add(List.of(k, v)));
        Csv.write(config.idMapFile(), List.of("anon_id", "source_folder"), rows);
    }

    private void writeLog(Instant started, List<Program> originals, List<Program> references, List<Program> sources,
                          List<Variant> variants, ControlFlowRewriter rewriter) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("generator_version", GENERATOR_VERSION);
        m.put("assignment", config.assignment());
        m.put("started_utc", started.toString());
        m.put("finished_utc", Instant.now().toString());
        m.put("java_version", System.getProperty("java.version"));
        m.put("seed", config.seed());
        m.put("submissions_dir", config.submissionsDir().toString());
        m.put("starter_paths", config.starterPaths().stream().map(Path::toString).toList());
        m.put("excluded_files", List.copyOf(excluded));
        m.put("reference_paths", config.referencePaths().stream().map(Path::toString).toList());
        m.put("originals", originals.size());
        m.put("originals_compiling", originals.stream().filter(Program::compiles).count());
        Map<String, String> notCompiling = new LinkedHashMap<>();
        originals.stream().filter(p -> !p.compiles()).forEach(p -> notCompiling.put(p.id(), firstLine(p.diagnostics())));
        m.put("originals_not_compiling", notCompiling);
        Map<String, Boolean> refs = new LinkedHashMap<>();
        references.forEach(r -> refs.put(r.id(), r.compiles()));
        m.put("references_compiling", refs);
        m.put("source_count_requested", config.sourceCount());
        m.put("sources", sources.stream().map(Program::id).toList());
        m.put("partial_levels", PARTIAL_LEVELS);
        Map<String, Map<String, Long>> byType = new TreeMap<>();
        for (Variant v : variants) {
            byType.computeIfAbsent(v.transformation().name(), k -> new TreeMap<>())
                    .merge(v.status().name(), 1L, Long::sum);
        }
        m.put("variants_by_type_and_status", byType);
        if (rewriter != null) {
            m.put("llm_provider", adapter.provider());
            m.put("llm_model_id", adapter.modelId());
            m.put("llm_effort", (adapter instanceof aiaa.llm.AnthropicAdapter aa ? (aa.effort() == null ? "model default" : aa.effort()) : "n/a"));
            m.put("llm_prompt_version", rewriter.promptVersion());
            m.put("llm_temperature", config.temperature() == null ? "provider default (not sent)" : config.temperature());
            m.put("llm_max_output_tokens", config.maxOutputTokens());
            m.put("llm_max_attempts", config.maxLlmAttempts());
            m.put("llm_input_tokens", variants.stream().mapToLong(Variant::llmInputTokens).sum());
            m.put("llm_output_tokens", variants.stream().mapToLong(Variant::llmOutputTokens).sum());
        }
        m.put("amount_copied_definition", "fraction of the variant's substantive non-starter lines (excluded files "
                + "not counted) that came from the source; 1.0 for whole-program variants");
        try {
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValue(config.outDir().resolve(LOG_JSON).toFile(), m);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
