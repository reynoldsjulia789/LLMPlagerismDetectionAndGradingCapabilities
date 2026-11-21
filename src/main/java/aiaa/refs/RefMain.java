package aiaa.refs;

import aiaa.data.AssignmentMaterials;
import aiaa.data.DataLayout;
import aiaa.data.HomeworkSpec;
import aiaa.llm.ModelAdapter;
import aiaa.llm.OpenAiAdapter;
import aiaa.pdm.SourceFiles;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Command-line entry point for AI reference solutions (generation and verification). */
public final class RefMain {

    private static final String USAGE = """
            AI reference solutions (generate with an LLM, verify automatically)

            Usage:
              RefMain --homework <Hw> [--homework <Hw>...] --adapter openai --model <id> [options]
              RefMain --verify <solution dir> --homework <Hw>           check an existing solution (no LLM)
              RefMain --homework <Hw>... --dry-run [--price-in X --price-out Y]   estimate tokens/cost

            Options:
              --data-root <dir>          default "HomeworkSubmissions-test data" (descriptions, starter, testers)
              --out-root <dir>           default references (gitignored: solutions to confidential assignments)
              --max-attempts <n>         default 3; compile errors / output differences are fed back
              --max-output-tokens <n>    default 16000
              --temperature <t>          omitted unless given
              --max-retries <n>          HTTP retries per call, default 4
              --timeout <seconds>        per program run during verification, default 120
              --force                    replace an existing reference for the same homework and model

            Verification compiles the solution with the instructor's UNCHANGED testers and runs it in a separate
            JVM: HW1/HW2 output must equal the description's CORRECT OUTPUT exactly; HW8 must print true/true
            and pass an independent check of both sorts. Results go to <out-root>/<Hw>/<model>/ (verified) or
            <model>.unverified/, with provenance.json. human_reviewed stays false until a person checks the code.
            Environment (openai): OPENAI_API_KEY (required), OPENAI_BASE_URL (optional)
            """;

    private RefMain() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    public static List<Object> run(String[] args) {
        List<String> homeworks = new ArrayList<>();
        Path dataRoot = Path.of(DataLayout.DEFAULT_ROOT);
        Path outRoot = Path.of("references");
        Path verifyDir = null;
        String adapterName = "none";
        String model = null;
        Double temperature = null;
        int maxOutputTokens = 16_000;
        int maxAttempts = 3;
        int maxRetries = 4;
        int timeout = 120;
        boolean force = false;
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
                case "--homework" -> homeworks.add(value(args, ++i, a));
                case "--data-root" -> dataRoot = Path.of(value(args, ++i, a));
                case "--out-root" -> outRoot = Path.of(value(args, ++i, a));
                case "--verify" -> verifyDir = Path.of(value(args, ++i, a));
                case "--adapter" -> adapterName = value(args, ++i, a).toLowerCase(Locale.ROOT);
                case "--model" -> model = value(args, ++i, a);
                case "--temperature" -> temperature = Double.parseDouble(value(args, ++i, a));
                case "--max-output-tokens" -> maxOutputTokens = Integer.parseInt(value(args, ++i, a));
                case "--max-attempts" -> maxAttempts = Integer.parseInt(value(args, ++i, a));
                case "--max-retries" -> maxRetries = Integer.parseInt(value(args, ++i, a));
                case "--timeout" -> timeout = Integer.parseInt(value(args, ++i, a));
                case "--force" -> force = true;
                case "--dry-run" -> dryRun = true;
                case "--price-in" -> priceIn = Double.parseDouble(value(args, ++i, a));
                case "--price-out" -> priceOut = Double.parseDouble(value(args, ++i, a));
                default -> throw new IllegalArgumentException("Unknown option " + a);
            }
        }
        if (homeworks.isEmpty()) {
            HomeworkSpec.KNOWN.forEach(h -> homeworks.add(h.name()));
        }
        List<Object> results = new ArrayList<>();

        if (verifyDir != null) {
            if (homeworks.size() != 1) {
                throw new IllegalArgumentException("--verify needs exactly one --homework");
            }
            AssignmentMaterials m = AssignmentMaterials.load(dataRoot, homeworks.getFirst());
            Map<String, String> files = new java.util.LinkedHashMap<>();
            SourceFiles.readJava(verifyDir).forEach((k, v) -> files.put(k.substring(k.lastIndexOf('/') + 1), v));
            SolutionVerifier.Result r = SolutionVerifier.verify(m, files, Duration.ofSeconds(timeout));
            System.out.println((r.verified() ? "VERIFIED  " : "FAILED    ") + verifyDir + ": " + r.detail());
            results.add(r);
            return results;
        }

        if (dryRun) {
            for (String hw : homeworks) {
                long[] e = ReferenceGenerator.estimate(AssignmentMaterials.load(dataRoot, hw));
                String cost = priceIn == null || priceOut == null ? ""
                        : String.format(Locale.ROOT, ", est. $%.3f (worst case x%d attempts)",
                        (e[0] * priceIn + e[1] * priceOut) / 1_000_000, maxAttempts);
                System.out.printf(Locale.ROOT, "DRY RUN %s: 1 call, ESTIMATED %,d input + %,d output tokens%s%n",
                        hw, e[0], e[1], cost);
                results.add(e);
            }
            return results;
        }

        ModelAdapter adapter = switch (adapterName) {
            case "openai" -> OpenAiAdapter.fromEnvironment(model, maxRetries);
            case "none" -> throw new IllegalArgumentException("--adapter openai is required to generate references");
            default -> throw new IllegalArgumentException("Unknown adapter " + adapterName);
        };
        ReferenceGenerator gen = new ReferenceGenerator(adapter, maxOutputTokens, temperature, maxAttempts,
                Duration.ofSeconds(timeout));
        for (String hw : homeworks) {
            results.add(report(hw, gen.generate(AssignmentMaterials.load(dataRoot, hw), outRoot, force)));
        }
        return results;
    }

    static ReferenceGenerator.Outcome report(String hw, ReferenceGenerator.Outcome o) {
        System.out.printf(Locale.ROOT, "%s: %s after %d attempt(s), %,d in / %,d out tokens -> %s%n", hw,
                o.verified() ? "VERIFIED" : "NOT VERIFIED", o.attempts(), o.inputTokens(), o.outputTokens(), o.dir());
        if (o.verification() != null) {
            System.out.println("  " + o.verification().detail());
        }
        if (o.error() != null) {
            System.out.println("  " + o.error());
        }
        return o;
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[i];
    }
}
