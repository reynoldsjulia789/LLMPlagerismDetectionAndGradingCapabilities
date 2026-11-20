package aiaa.pdm;

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
              PdmMain --assignment <label> --submissions <dir> [options]

            Required:
              --assignment <label>       e.g. F25Hw1 (only submissions in one folder are compared)
              --submissions <dir>        folder containing one subfolder per submission

            Starter material:
              --starter <path>           starter code dir/zip/file (repeatable)
              --exclude-file <name>      file dropped in strip mode, e.g. MyLinkedListTester.java (repeatable)
              --starter-mode strip|keep  default strip; use keep for the without-exclusion comparison

            AI reference bank:
              --reference <path>         AI-generated reference solution dir/zip/file (repeatable)

            Similarity model:
              --adapter none|mock|openai default none (token baseline only)
              --model <id>               exact model ID (required for openai; recorded in outputs)
              --temperature <t>          omitted from requests unless given
              --max-output-tokens <n>    default 1500
              --max-input-chars <n>      default 120000; larger prompts are skipped, never truncated
              --max-retries <n>          default 4
              --parallelism <n>          concurrent LLM calls, default 4
              --max-llm-pairs <n>        only send the first n peer pairs (smoke tests)
              --resume                   reuse successful responses saved in the output folder

            Scoring / output:
              --alpha <a>                peer weight in S_i, default 0.5
              --token-k <k>              k-gram length for the token baseline, default 5
              --out <dir>                default pdm-output/<assignment>/<starter-mode>
              --id-map <file>            default private/id_map_<assignment>.csv (keep out of the archive)
              --price-in <usd>           USD per 1M input tokens (for cost estimates)
              --price-out <usd>          USD per 1M output tokens
              --pricing-source <text>    where the prices came from (URL and date)

            Environment (openai): OPENAI_API_KEY (required), OPENAI_BASE_URL (optional)
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

    static PdmRunner.Summary run(String[] args) {
        String assignment = null;
        Path submissions = null;
        List<Path> starters = new ArrayList<>();
        Set<String> excluded = new LinkedHashSet<>();
        List<Path> references = new ArrayList<>();
        StarterMode mode = StarterMode.STRIP;
        String adapterName = "none";
        String model = null;
        Double temperature = null;
        Integer maxOutputTokens = 1500;
        int maxInputChars = 120_000;
        int maxRetries = 4;
        int parallelism = 4;
        Integer maxPairs = null;
        boolean resume = false;
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
                case "--submissions" -> submissions = Path.of(value(args, ++i, a));
                case "--starter" -> starters.add(Path.of(value(args, ++i, a)));
                case "--exclude-file" -> excluded.add(value(args, ++i, a));
                case "--starter-mode" -> mode = StarterMode.valueOf(value(args, ++i, a).toUpperCase(Locale.ROOT));
                case "--reference" -> references.add(Path.of(value(args, ++i, a)));
                case "--adapter" -> adapterName = value(args, ++i, a).toLowerCase(Locale.ROOT);
                case "--model" -> model = value(args, ++i, a);
                case "--temperature" -> temperature = Double.parseDouble(value(args, ++i, a));
                case "--max-output-tokens" -> maxOutputTokens = Integer.parseInt(value(args, ++i, a));
                case "--max-input-chars" -> maxInputChars = Integer.parseInt(value(args, ++i, a));
                case "--max-retries" -> maxRetries = Integer.parseInt(value(args, ++i, a));
                case "--parallelism" -> parallelism = Integer.parseInt(value(args, ++i, a));
                case "--max-llm-pairs" -> maxPairs = Integer.parseInt(value(args, ++i, a));
                case "--resume" -> resume = true;
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
        if (assignment == null || submissions == null) {
            throw new IllegalArgumentException("--assignment and --submissions are required");
        }
        if (out == null) {
            out = Path.of("pdm-output", assignment, mode.name().toLowerCase(Locale.ROOT));
        }
        if (idMap == null) {
            idMap = Path.of("private", "id_map_" + assignment + ".csv");
        }

        ModelAdapter adapter = switch (adapterName) {
            case "none" -> null;
            case "mock" -> new MockAdapter(model == null ? "mock-token-jaccard" : model,
                    LlmSimilarityJudge.mockResponder());
            case "openai" -> OpenAiAdapter.fromEnvironment(model, maxRetries);
            default -> throw new IllegalArgumentException("Unknown adapter " + adapterName);
        };

        PdmConfig config = new PdmConfig(assignment, submissions, starters, excluded, references, mode, alpha,
                tokenK, out, idMap, parallelism, maxPairs, maxOutputTokens, temperature, maxInputChars, maxRetries,
                priceIn, priceOut, pricingSource, resume);
        PdmRunner.Summary s = new PdmRunner(config, adapter).run();
        System.out.printf(Locale.ROOT, "PDM %s (%s): %d eligible, %d excluded, %d references, %d peer pairs%n",
                assignment, mode, s.eligibleSubmissions(), s.excludedSubmissions(), s.references(), s.peerPairs());
        if (adapter != null) {
            System.out.printf(Locale.ROOT, "LLM: %d calls, %d failures, %d in / %d out tokens%s%n",
                    s.llmCalls(), s.llmFailures(), s.inputTokens(), s.outputTokens(),
                    s.costUsd() == null ? "" : String.format(Locale.ROOT, ", est. $%.4f", s.costUsd()));
        }
        System.out.printf(Locale.ROOT, "Wrote %s (%.1fs)%n", s.outDir().toAbsolutePath(), s.wallMs() / 1000.0);
        return s;
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[i];
    }
}
