package aiaa.synth;

import aiaa.llm.ModelAdapter;
import aiaa.llm.OpenAiAdapter;

import java.nio.file.Path;
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
              SynthMain --assignment <label> --submissions <dir> [options]

            Required:
              --assignment <label>       e.g. F25Hw1
              --submissions <dir>        folder containing one subfolder per original submission

            Options:
              --starter <path>           starter code dir/zip/file (repeatable); used to measure amount copied
              --exclude-file <name>      supplied file not counted or rewritten, e.g. Tester.java (repeatable)
              --reference <path>         AI reference solution to derive AI-copy variants from (repeatable)
              --sources <n>              student submissions to derive variants from, default 8
              --seed <n>                 master random seed, default 42
              --out <dir>                default synthetic-data/<assignment>
              --id-map <file>            default private/synth_id_map_<assignment>.csv
              --adapter none|openai      LLM for CONTROL_FLOW variants, default none (those are skipped)
              --model <id>               exact model ID (required for openai)
              --temperature <t>          omitted unless given
              --max-output-tokens <n>    default 16000
              --max-llm-attempts <n>     default 3 (compile errors are fed back between attempts)
              --max-retries <n>          HTTP retries per call, default 4

            Environment (openai): OPENAI_API_KEY (required), OPENAI_BASE_URL (optional)
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

    static SyntheticGenerator.Summary run(String[] args) {
        String assignment = null;
        Path submissions = null;
        List<Path> starters = new ArrayList<>();
        Set<String> excluded = new LinkedHashSet<>();
        List<Path> references = new ArrayList<>();
        int sources = 8;
        long seed = 42;
        Path out = null;
        Path idMap = null;
        String adapterName = "none";
        String model = null;
        Double temperature = null;
        int maxOutputTokens = 16_000;
        int maxAttempts = 3;
        int maxRetries = 4;
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
                case "--reference" -> references.add(Path.of(value(args, ++i, a)));
                case "--sources" -> sources = Integer.parseInt(value(args, ++i, a));
                case "--seed" -> seed = Long.parseLong(value(args, ++i, a));
                case "--out" -> out = Path.of(value(args, ++i, a));
                case "--id-map" -> idMap = Path.of(value(args, ++i, a));
                case "--adapter" -> adapterName = value(args, ++i, a).toLowerCase(Locale.ROOT);
                case "--model" -> model = value(args, ++i, a);
                case "--temperature" -> temperature = Double.parseDouble(value(args, ++i, a));
                case "--max-output-tokens" -> maxOutputTokens = Integer.parseInt(value(args, ++i, a));
                case "--max-llm-attempts" -> maxAttempts = Integer.parseInt(value(args, ++i, a));
                case "--max-retries" -> maxRetries = Integer.parseInt(value(args, ++i, a));
                default -> throw new IllegalArgumentException("Unknown option " + a);
            }
        }
        if (assignment == null || submissions == null) {
            throw new IllegalArgumentException("--assignment and --submissions are required");
        }
        if (out == null) {
            out = Path.of("synthetic-data", assignment);
        }
        if (idMap == null) {
            idMap = Path.of("private", "synth_id_map_" + assignment + ".csv");
        }
        ModelAdapter adapter = switch (adapterName) {
            case "none" -> null;
            case "openai" -> OpenAiAdapter.fromEnvironment(model, maxRetries);
            default -> throw new IllegalArgumentException("Unknown adapter " + adapterName);
        };
        SynthConfig config = new SynthConfig(assignment, submissions, starters, excluded, references, sources, seed,
                out, idMap, maxOutputTokens, temperature, maxAttempts);
        SyntheticGenerator.Summary s = new SyntheticGenerator(config, adapter).run();

        Map<String, int[]> byType = new TreeMap<>(); // ok, failed, skipped
        for (SyntheticGenerator.Variant v : s.variants()) {
            int[] c = byType.computeIfAbsent(v.transformation().name(), k -> new int[3]);
            c[v.status().ordinal()]++;
        }
        System.out.printf(Locale.ROOT, "Synthetic %s: %d originals (%d compile), %d variants OK of %d%n",
                assignment, s.originals(), s.originalsCompiling(), s.ok(), s.variants().size());
        byType.forEach((k, c) -> System.out.printf(Locale.ROOT, "  %-13s ok %d, failed %d, skipped %d%n",
                k, c[0], c[1], c[2]));
        System.out.println("Wrote " + s.outDir().toAbsolutePath());
        return s;
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[i];
    }
}
