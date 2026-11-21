package aiaa.synth;

import aiaa.data.DataLayout;
import aiaa.llm.ModelAdapter;
import aiaa.llm.OpenAiAdapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Command-line entry point for the synthetic-copy generator. Run with --help for usage. */
public final class SynthMain {

    private static final String USAGE = """
            Synthetic copy generator (labeled plagiarism test cases)

            Usage:
              SynthMain --homework Hw1 [--cohort F25] [options]           (dataset layout, see README)
              SynthMain --assignment <label> --submissions <dir> [options]

            Dataset layout:
              --homework <Hw1|Hw2|Hw8>   homework to generate for; fills in its starter code and tester exclusions
              --cohort <F25|Sp26|...>    one cohort only; default: every cohort of the homework (each separately)
              --data-root <dir>          input root, default "HomeworkSubmissions-test data"
              --out-root <dir>           output root, default synthetic-data. Written in the same layout
                                         (<out-root>/HomeworkSubmissions/<Hw>/<cohort>/) with the starter code
                                         copied to <out-root>/HomeworkDescriptions/, so it can be screened with
                                         PdmMain --data-root <out-root> --homework <Hw>

            Explicit folders (instead of --homework):
              --assignment <label>       cohort label used for IDs, e.g. F25Hw1
              --submissions <dir>        folder containing one subfolder per original submission
              --out <dir>                default synthetic-data/<assignment>

            Options:
              --starter <path>           starter code dir/zip/file (repeatable; overrides the homework default)
              --exclude-file <name>      supplied file not counted or rewritten (repeatable; overrides the default)
              --reference <path>         AI reference solution to derive AI-copy variants from (repeatable)
              --sources <n>              student submissions to derive variants from, default 8
              --seed <n>                 master random seed, default 42
              --private-dir <dir>        where anonymous-ID mappings go, default private (gitignored)
              --id-map <file>            exact mapping file (single cohort only), default
                                         <private-dir>/synth_id_map_<cohort>.csv
              --adapter <name>           LLM for CONTROL_FLOW variants: none (default; skipped) | anthropic | openai
              --model <id>               exact model ID (required for anthropic/openai)
              --effort <level>           Claude only: low|medium|high|xhigh|max (default: the model's own default)
              --temperature <t>          omitted unless given
              --max-output-tokens <n>    default 16000
              --max-llm-attempts <n>     default 3 (compile errors are fed back between attempts)
              --max-retries <n>          HTTP retries per call, default 4
              --dry-run                  estimate the CONTROL_FLOW LLM calls, tokens and cost without calling a
                                         model or writing data; no API key needed
              --price-in <usd>           USD per 1M input tokens (dry-run cost estimate)
              --price-out <usd>          USD per 1M output tokens

            Environment: ANTHROPIC_API_KEY for --adapter anthropic; OPENAI_API_KEY (+ optional OPENAI_BASE_URL)
            for --adapter openai. Keys are read from the environment only, never from files in the repo.
            """;

    private SynthMain() {
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

    static List<SyntheticGenerator.Summary> run(String[] args) {
        String assignment = null;
        Path submissions = null;
        String homework = null;
        String cohort = null;
        Path dataRoot = Path.of(DataLayout.DEFAULT_ROOT);
        Path outRoot = Path.of("synthetic-data");
        List<Path> starters = new ArrayList<>();
        Set<String> excluded = new LinkedHashSet<>();
        List<Path> references = new ArrayList<>();
        int sources = 8;
        long seed = 42;
        Path out = null;
        Path idMap = null;
        Path privateDir = Path.of("private");
        String adapterName = "none";
        String model = null;
        String effort = null;
        Double temperature = null;
        int maxOutputTokens = 16_000;
        int maxAttempts = 3;
        int maxRetries = 4;
        boolean dryRun = false;
        Double priceIn = null;
        Double priceOut = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--help", "-h" -> {
                    System.out.println(USAGE);
                    return List.of();
                }
                case "--assignment" -> assignment = value(args, ++i, a);
                case "--submissions" -> submissions = Path.of(value(args, ++i, a));
                case "--homework" -> homework = value(args, ++i, a);
                case "--cohort" -> cohort = value(args, ++i, a);
                case "--data-root" -> dataRoot = Path.of(value(args, ++i, a));
                case "--out-root" -> outRoot = Path.of(value(args, ++i, a));
                case "--starter" -> starters.add(Path.of(value(args, ++i, a)));
                case "--exclude-file" -> excluded.add(value(args, ++i, a));
                case "--reference" -> references.add(Path.of(value(args, ++i, a)));
                case "--sources" -> sources = Integer.parseInt(value(args, ++i, a));
                case "--seed" -> seed = Long.parseLong(value(args, ++i, a));
                case "--out" -> out = Path.of(value(args, ++i, a));
                case "--id-map" -> idMap = Path.of(value(args, ++i, a));
                case "--private-dir" -> privateDir = Path.of(value(args, ++i, a));
                case "--adapter" -> adapterName = value(args, ++i, a).toLowerCase(Locale.ROOT);
                case "--model" -> model = value(args, ++i, a);
                case "--effort" -> effort = value(args, ++i, a);
                case "--temperature" -> temperature = Double.parseDouble(value(args, ++i, a));
                case "--max-output-tokens" -> maxOutputTokens = Integer.parseInt(value(args, ++i, a));
                case "--max-llm-attempts" -> maxAttempts = Integer.parseInt(value(args, ++i, a));
                case "--max-retries" -> maxRetries = Integer.parseInt(value(args, ++i, a));
                case "--dry-run" -> dryRun = true;
                case "--price-in" -> priceIn = Double.parseDouble(value(args, ++i, a));
                case "--price-out" -> priceOut = Double.parseDouble(value(args, ++i, a));
                default -> throw new IllegalArgumentException("Unknown option " + a);
            }
        }

        record Job(String label, Path dir, Path out, Path idMap) {
        }
        List<Job> jobs = new ArrayList<>();
        if (homework != null) {
            if (submissions != null || out != null) {
                throw new IllegalArgumentException("--homework uses --out-root; do not combine with --submissions/--out");
            }
            DataLayout.Selection sel = DataLayout.resolve(dataRoot, homework, cohort);
            if (starters.isEmpty()) {
                starters.addAll(sel.starterPaths());
            }
            if (excluded.isEmpty()) {
                excluded.addAll(sel.excludedFiles());
            }
            copyStarters(starters, outRoot.resolve(DataLayout.DESCRIPTIONS));
            for (DataLayout.Cohort c : sel.cohorts()) {
                Path cohortOut = DataLayout.homeworkDir(outRoot, sel.homework().name()).resolve(c.label());
                jobs.add(new Job(c.label(), c.dir(), cohortOut,
                        idMap != null && sel.cohorts().size() == 1 ? idMap : defaultIdMap(privateDir, c.label())));
            }
        } else {
            if (assignment == null || submissions == null) {
                throw new IllegalArgumentException("give --homework, or --assignment and --submissions");
            }
            jobs.add(new Job(assignment, submissions, out == null ? Path.of("synthetic-data", assignment) : out,
                    idMap == null ? defaultIdMap(privateDir, assignment) : idMap));
        }

        if (dryRun) {
            for (Job job : jobs) {
                SynthConfig config = new SynthConfig(job.label(), job.dir(), starters, excluded, references, sources,
                        seed, job.out(), null, maxOutputTokens, temperature, maxAttempts);
                SyntheticGenerator.ControlFlowEstimate e = new SyntheticGenerator(config, null).estimateControlFlow();
                System.out.printf(Locale.ROOT, "DRY RUN %s: %d CONTROL_FLOW calls (up to %d with compile retries); "
                        + "ESTIMATED %,d input + %,d output tokens per pass", job.label(), e.calls(),
                        e.maxCallsWithRetries(), e.inputTokens(), e.outputTokens());
                if (priceIn != null && priceOut != null) {
                    double one = (e.inputTokens() * priceIn + e.outputTokens() * priceOut) / 1_000_000;
                    System.out.printf(Locale.ROOT, "; est. $%.2f (worst case $%.2f)", one, one * maxAttempts);
                }
                System.out.println();
            }
            return List.of();
        }

        ModelAdapter adapter = switch (adapterName) {
            case "none" -> null;
            case "anthropic" -> aiaa.llm.AnthropicAdapter.fromEnvironment(model, effort, maxRetries);
            case "openai" -> OpenAiAdapter.fromEnvironment(model, maxRetries);
            default -> throw new IllegalArgumentException("Unknown adapter " + adapterName);
        };

        List<SyntheticGenerator.Summary> summaries = new ArrayList<>();
        for (Job job : jobs) {
            SynthConfig config = new SynthConfig(job.label(), job.dir(), starters, excluded, references, sources,
                    seed, job.out(), job.idMap(), maxOutputTokens, temperature, maxAttempts);
            SyntheticGenerator.Summary s = new SyntheticGenerator(config, adapter).run();
            print(job.label(), s);
            summaries.add(s);
        }
        return summaries;
    }

    private static Path defaultIdMap(Path privateDir, String label) {
        return privateDir.resolve("synth_id_map_" + label + ".csv");
    }

    /** Copies starter archives next to the generated data so the output root is self-contained. */
    private static void copyStarters(List<Path> starters, Path descriptionsDir) {
        try {
            for (Path p : starters) {
                if (Files.isRegularFile(p)) {
                    Files.createDirectories(descriptionsDir);
                    Files.copy(p, descriptionsDir.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot copy starter code", e);
        }
    }

    private static void print(String label, SyntheticGenerator.Summary s) {
        Map<String, int[]> byType = new TreeMap<>(); // ok, failed, skipped
        for (SyntheticGenerator.Variant v : s.variants()) {
            int[] c = byType.computeIfAbsent(v.transformation().name(), k -> new int[3]);
            c[v.status().ordinal()]++;
        }
        System.out.printf(Locale.ROOT, "Synthetic %s: %d originals (%d compile), %d variants OK of %d%n",
                label, s.originals(), s.originalsCompiling(), s.ok(), s.variants().size());
        byType.forEach((k, c) -> System.out.printf(Locale.ROOT, "  %-13s ok %d, failed %d, skipped %d%n",
                k, c[0], c[1], c[2]));
        System.out.println("Wrote " + s.outDir().toAbsolutePath());
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[i];
    }
}
