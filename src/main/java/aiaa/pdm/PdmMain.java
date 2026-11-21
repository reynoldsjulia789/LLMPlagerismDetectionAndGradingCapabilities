package aiaa.pdm;

import aiaa.data.DataLayout;
import aiaa.llm.MockAdapter;
import aiaa.llm.ModelAdapter;
import aiaa.llm.OpenAiAdapter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Command-line entry point for the Plagiarism Detection Module. Run with --help for usage. */
public final class PdmMain {

    private static final String USAGE = """
            Plagiarism Detection Module (PDM)

            Usage:
              PdmMain --homework Hw1 [--cohort F25] [options]          (dataset layout, see README)
              PdmMain --assignment <label> --submissions <dir>... [options]

            Dataset layout (<root>/HomeworkSubmissions/<Hw>/<Cohort><Hw>/<submission>/):
              --homework <Hw1|Hw2|Hw8>   homework to screen; fills in its starter code and tester exclusions
              --cohort <F25|Sp26|...>    one cohort only; default: all cohorts of the homework, pooled,
                                         with every pair tagged WITHIN or CROSS cohort
              --data-root <dir>          default "HomeworkSubmissions-test data"; a generated synthetic
                                         dataset (e.g. synthetic-data) can be used as the root too
              --controls <dir>           negative controls root, default negative-controls; its <Hw>/
                                         folder is added as cohort "Controls" when it exists
              --no-controls              do not add negative controls

            Explicit folders (instead of --homework):
              --assignment <label>       run label, e.g. F25Hw1
              --submissions <dir>        folder with one subfolder per submission (repeatable: each folder is
                                         a cohort labeled by its folder name, pooled like --homework)

            Starter material (override the --homework defaults):
              --starter <path>           starter code dir/zip/file (repeatable)
              --exclude-file <name>      file dropped in strip mode, e.g. MyLinkedListTester.java (repeatable)
              --starter-mode strip|keep  default strip; use keep for the without-exclusion comparison

            AI reference bank:
              --reference <path>         AI-generated reference solution dir/zip/file (repeatable)

            Similarity model:
              --adapter <name>           none (token baseline only, default) | anthropic | openai | mock
              --model <id>               exact model ID (required for anthropic/openai; recorded in outputs)
              --effort <level>           Claude only: low|medium|high|xhigh|max (default: the model's own default)
              --temperature <t>          omitted from requests unless given
              --max-output-tokens <n>    default 16000 (Claude thinks before answering; thinking counts here)
              --max-input-chars <n>      default 120000; larger prompts are skipped, never truncated
              --max-retries <n>          default 4
              --parallelism <n>          concurrent LLM calls, default 4
              --max-llm-pairs <n>        only send the first n peer pairs (smoke tests)
              --resume                   reuse successful responses saved in the output folder
              --ids-from-folders         use folder names as IDs (already-anonymized datasets such as
                                         synthetic-data/<assignment>/submissions); no ID map is written
              --dry-run                  build every LLM prompt and estimate calls, tokens and cost (with
                                         --price-in/--price-out) without calling any model; no API key needed
              --assumed-output-tokens <n> output tokens per call assumed by --dry-run, default 400

            Scoring / output:
              --alpha <a>                peer weight in S_i, default 0.5
              --token-k <k>              k-gram length for the token baseline, default 5
              --out <dir>                default pdm-output/<assignment>/<starter-mode>
              --id-map <file>            default private/id_map_<assignment>.csv (keep out of the archive)
              --price-in <usd>           USD per 1M input tokens (for cost estimates)
              --price-out <usd>          USD per 1M output tokens
              --pricing-source <text>    where the prices came from (URL and date)

            Environment: ANTHROPIC_API_KEY for --adapter anthropic; OPENAI_API_KEY (+ optional OPENAI_BASE_URL)
            for --adapter openai. Keys are read from the environment only, never from files in the repo.
            """;

    private PdmMain() {
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

    public static PdmRunner.Summary run(String[] args) {
        String assignment = null;
        List<Path> submissions = new ArrayList<>();
        String homework = null;
        String cohort = null;
        Path dataRoot = Path.of(DataLayout.DEFAULT_ROOT);
        Path controlsRoot = Path.of(DataLayout.DEFAULT_CONTROLS_ROOT);
        List<Path> starters = new ArrayList<>();
        Set<String> excluded = new LinkedHashSet<>();
        List<Path> references = new ArrayList<>();
        StarterMode mode = StarterMode.STRIP;
        String adapterName = "none";
        String model = null;
        String effort = null;
        Double temperature = null;
        Integer maxOutputTokens = 16_000;
        int maxInputChars = 120_000;
        int maxRetries = 4;
        int parallelism = 4;
        Integer maxPairs = null;
        boolean resume = false;
        boolean idsFromFolders = false;
        boolean dryRun = false;
        int assumedOutputTokens = 400;
        double alpha = 0.5;
        int tokenK = TokenSimilarity.DEFAULT_K;
        Path out = null;
        Path idMap = null;
        Double priceIn = null;
        Double priceOut = null;
        String pricingSource = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--help", "-h" -> {
                    System.out.println(USAGE);
                    return null;
                }
                case "--assignment" -> assignment = value(args, ++i, a);
                case "--submissions" -> submissions.add(Path.of(value(args, ++i, a)));
                case "--homework" -> homework = value(args, ++i, a);
                case "--cohort" -> cohort = value(args, ++i, a);
                case "--data-root" -> dataRoot = Path.of(value(args, ++i, a));
                case "--controls" -> controlsRoot = Path.of(value(args, ++i, a));
                case "--no-controls" -> controlsRoot = null;
                case "--starter" -> starters.add(Path.of(value(args, ++i, a)));
                case "--exclude-file" -> excluded.add(value(args, ++i, a));
                case "--starter-mode" -> mode = StarterMode.valueOf(value(args, ++i, a).toUpperCase(Locale.ROOT));
                case "--reference" -> references.add(Path.of(value(args, ++i, a)));
                case "--adapter" -> adapterName = value(args, ++i, a).toLowerCase(Locale.ROOT);
                case "--model" -> model = value(args, ++i, a);
                case "--effort" -> effort = value(args, ++i, a);
                case "--temperature" -> temperature = Double.parseDouble(value(args, ++i, a));
                case "--max-output-tokens" -> maxOutputTokens = Integer.parseInt(value(args, ++i, a));
                case "--max-input-chars" -> maxInputChars = Integer.parseInt(value(args, ++i, a));
                case "--max-retries" -> maxRetries = Integer.parseInt(value(args, ++i, a));
                case "--parallelism" -> parallelism = Integer.parseInt(value(args, ++i, a));
                case "--max-llm-pairs" -> maxPairs = Integer.parseInt(value(args, ++i, a));
                case "--resume" -> resume = true;
                case "--ids-from-folders" -> idsFromFolders = true;
                case "--dry-run" -> dryRun = true;
                case "--assumed-output-tokens" -> assumedOutputTokens = Integer.parseInt(value(args, ++i, a));
                case "--alpha" -> alpha = Double.parseDouble(value(args, ++i, a));
                case "--token-k" -> tokenK = Integer.parseInt(value(args, ++i, a));
                case "--out" -> out = Path.of(value(args, ++i, a));
                case "--id-map" -> idMap = Path.of(value(args, ++i, a));
                case "--price-in" -> priceIn = Double.parseDouble(value(args, ++i, a));
                case "--price-out" -> priceOut = Double.parseDouble(value(args, ++i, a));
                case "--pricing-source" -> pricingSource = value(args, ++i, a);
                default -> throw new IllegalArgumentException("Unknown option " + a);
            }
        }
        List<DataLayout.Cohort> cohorts = new ArrayList<>();
        if (homework != null) {
            if (!submissions.isEmpty()) {
                throw new IllegalArgumentException("use either --homework or --submissions, not both");
            }
            DataLayout.Selection sel = DataLayout.resolve(dataRoot, homework, cohort);
            cohorts.addAll(sel.cohorts());
            DataLayout.controls(controlsRoot, sel.homework().name()).ifPresent(cohorts::add);
            if (assignment == null) {
                assignment = sel.label();
            }
            if (starters.isEmpty()) {
                starters.addAll(sel.starterPaths());
            }
            if (excluded.isEmpty()) {
                excluded.addAll(sel.excludedFiles());
            }
        } else {
            if (assignment == null || submissions.isEmpty()) {
                throw new IllegalArgumentException("give --homework, or --assignment and --submissions");
            }
            for (Path p : submissions) {
                cohorts.add(new DataLayout.Cohort(p.getFileName().toString(), p));
            }
        }
        if (out == null) {
            out = Path.of("pdm-output", assignment, mode.name().toLowerCase(Locale.ROOT));
        }
        if (idMap == null) {
            idMap = Path.of("private", "id_map_" + assignment + ".csv");
        }

        if (dryRun) {
            PdmConfig dry = new PdmConfig(assignment, cohorts, starters, excluded, references, mode, alpha,
                    tokenK, out, null, parallelism, maxPairs, maxOutputTokens, temperature, maxInputChars, maxRetries,
                    priceIn, priceOut, pricingSource, false, idsFromFolders);
            PdmRunner.Estimate e = new PdmRunner(dry, null).estimate(model == null ? "unspecified" : model,
                    assumedOutputTokens);
            printEstimate(assignment, mode, e, priceIn != null && priceOut != null, assumedOutputTokens);
            return null;
        }

        ModelAdapter adapter = switch (adapterName) {
            case "none" -> null;
            case "mock" -> new MockAdapter(model == null ? "mock-token-jaccard" : model,
                    LlmSimilarityJudge.mockResponder());
            case "anthropic" -> aiaa.llm.AnthropicAdapter.fromEnvironment(model, effort, maxRetries);
            case "openai" -> OpenAiAdapter.fromEnvironment(model, maxRetries);
            default -> throw new IllegalArgumentException("Unknown adapter " + adapterName);
        };

        PdmConfig config = new PdmConfig(assignment, cohorts, starters, excluded, references, mode, alpha,
                tokenK, out, idMap, parallelism, maxPairs, maxOutputTokens, temperature, maxInputChars, maxRetries,
                priceIn, priceOut, pricingSource, resume, idsFromFolders);
        PdmRunner.Summary s = new PdmRunner(config, adapter).run();
        System.out.printf(Locale.ROOT, "PDM %s (%s): %d cohort(s) %s, %d eligible, %d excluded, %d references, "
                        + "%d peer pairs%n", assignment, mode, cohorts.size(),
                cohorts.stream().map(DataLayout.Cohort::label).toList(), s.eligibleSubmissions(),
                s.excludedSubmissions(), s.references(), s.peerPairs());
        if (adapter != null) {
            System.out.printf(Locale.ROOT, "LLM: %d calls, %d failures, %d in / %d out tokens%s%n",
                    s.llmCalls(), s.llmFailures(), s.inputTokens(), s.outputTokens(),
                    s.costUsd() == null ? "" : String.format(Locale.ROOT, ", est. $%.4f", s.costUsd()));
        }
        System.out.printf(Locale.ROOT, "Wrote %s (%.1fs)%n", s.outDir().toAbsolutePath(), s.wallMs() / 1000.0);
        return s;
    }

    private static void printEstimate(String assignment, StarterMode mode, PdmRunner.Estimate e, boolean priced,
                                      int assumedOut) {
        System.out.printf(Locale.ROOT, "DRY RUN %s (%s): %d submissions -> %d peer + %d AI-reference calls"
                + " (%d prompts too large, would be skipped)%n", assignment, mode, e.submissions(), e.peerCalls(),
                e.aiCalls(), e.tooLarge());
        System.out.printf(Locale.ROOT, "  ESTIMATED tokens: %,d input (%.0f per peer call, at %.1f chars/token)"
                + " + %,d output (%d assumed per call)%n", e.inputTokens(), e.inputTokensPerPeerCall(),
                aiaa.llm.TokenEstimate.CHARS_PER_TOKEN, e.outputTokens(), assumedOut);
        System.out.printf(Locale.ROOT, "  EXTRAPOLATED to 100 submissions (4,950 peer pairs): %,d input + %,d output%n",
                e.extrapolatedInputTokens100(), e.extrapolatedOutputTokens100());
        if (priced) {
            System.out.printf(Locale.ROOT, "  ESTIMATED cost: $%.2f for this run; $%.2f per 100-submission benchmark%n",
                    e.costUsd(), e.extrapolatedCostUsd100());
        } else {
            System.out.println("  (add --price-in/--price-out, USD per 1M tokens, for a cost estimate)");
        }
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[i];
    }
}
