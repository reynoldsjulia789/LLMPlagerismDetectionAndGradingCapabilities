package aiaa.refs;

import aiaa.data.AssignmentMaterials;
import aiaa.llm.ModelAdapter;
import aiaa.llm.ModelException;
import aiaa.llm.ModelRequest;
import aiaa.llm.ModelResponse;
import aiaa.llm.TokenEstimate;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generates an AI reference solution for one homework and verifies it automatically
 * ({@link SolutionVerifier}). Failed attempts are retried with the compiler errors or the first
 * output difference fed back. The result is written with full provenance; an unverified result goes
 * to a {@code .unverified} folder so it cannot be mistaken for a usable reference.
 *
 * <p>Automatic verification is necessary but not sufficient: the spec requires an independent check
 * against the assignment, so {@code provenance.json} has {@code human_reviewed: false} until a person
 * has read the code and flipped it.
 */
public final class ReferenceGenerator {

    static final String SYSTEM_RESOURCE = "prompts/reference_solution_system_v1.txt";
    static final String SCHEMA_RESOURCE = "prompts/reference_solution_schema_v1.json";
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public record Outcome(boolean verified, Path dir, int attempts, long inputTokens, long outputTokens,
                          SolutionVerifier.Result verification, String error) {
    }

    private final ModelAdapter adapter;
    private final Integer maxOutputTokens;
    private final Double temperature;
    private final int maxAttempts;
    private final Duration runTimeout;

    public ReferenceGenerator(ModelAdapter adapter, Integer maxOutputTokens, Double temperature, int maxAttempts,
                              Duration runTimeout) {
        this.adapter = adapter;
        this.maxOutputTokens = maxOutputTokens;
        this.temperature = temperature;
        this.maxAttempts = maxAttempts;
        this.runTimeout = runTimeout;
    }

    public static String promptVersion() {
        return "reference_solution_v1@" + sha256(SolutionVerifier.resource(SYSTEM_RESOURCE) + "\u0000"
                + SolutionVerifier.resource(SCHEMA_RESOURCE)).substring(0, 10);
    }

    /** Folder name for a model ID: safe characters only. */
    static String slug(String modelId) {
        return modelId.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    public Outcome generate(AssignmentMaterials m, Path outRoot, boolean force) {
        Path verifiedDir = outRoot.resolve(m.homework().name()).resolve(slug(adapter.modelId()));
        Path unverifiedDir = outRoot.resolve(m.homework().name()).resolve(slug(adapter.modelId()) + ".unverified");
        if (!force && (Files.exists(verifiedDir) || Files.exists(unverifiedDir))) {
            throw new IllegalStateException("A reference already exists for " + m.homework().name() + " / "
                    + adapter.modelId() + " (pass --force to regenerate; the old one is replaced)");
        }
        String system = SolutionVerifier.resource(SYSTEM_RESOURCE);
        String schema = SolutionVerifier.resource(SCHEMA_RESOURCE);
        long in = 0;
        long out = 0;
        String feedback = null;
        String returnedModel = null;
        String notes = null;
        Map<String, String> files = Map.of();
        SolutionVerifier.Result last = null;
        String error = null;
        int attempt;
        for (attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ModelResponse r = adapter.complete(new ModelRequest(system, userPrompt(m, feedback), maxOutputTokens,
                        temperature, "reference_solution", schema));
                in += r.inputTokens();
                out += r.outputTokens();
                returnedModel = r.returnedModel();
                JsonNode root = JSON.readTree(r.text());
                notes = root.path("notes").asText("");
                files = new LinkedHashMap<>();
                for (JsonNode f : root.path("files")) {
                    String name = f.path("name").asText().replaceAll(".*[/\\\\]", "");
                    if (name.endsWith(".java") && !m.testerFiles().containsKey(name)) {
                        files.put(name, f.path("content").asText());
                    }
                }
                if (files.isEmpty()) {
                    feedback = "Your previous response contained no .java files besides the supplied tester.";
                    continue;
                }
                last = SolutionVerifier.verify(m, files, runTimeout);
                if (last.verified()) {
                    error = null;
                    break;
                }
                feedback = "Your previous solution failed automatic verification: " + last.detail()
                        + "\nFix it and return every file again.";
            } catch (ModelException e) {
                error = "model error: " + e.getMessage();
                in += 0;
            } catch (IOException e) {
                feedback = "Your previous response was not valid JSON matching the schema.";
            }
        }
        boolean verified = last != null && last.verified();
        int attempts = Math.min(attempt, maxAttempts);
        Path dir = verified ? verifiedDir : unverifiedDir;
        write(dir, files, provenance(m, returnedModel, notes, attempts, in, out, last, error, verified));
        return new Outcome(verified, dir, attempts, in, out, last, error);
    }

    static String userPrompt(AssignmentMaterials m, String feedback) {
        StringBuilder sb = new StringBuilder();
        sb.append("Write the complete solution for this assignment.\n\n<<<ASSIGNMENT_DESCRIPTION\n")
                .append(m.description().strip()).append("\nASSIGNMENT_DESCRIPTION>>>\n\n");
        if (!m.starterFiles().isEmpty()) {
            sb.append("Instructor starter code (complete these files):\n\n");
            m.starterFiles().forEach((n, c) -> sb.append("<<<FILE ").append(n).append('\n').append(c).append("\nFILE>>>\n\n"));
        }
        if (!m.testerFiles().isEmpty()) {
            sb.append("Supplied tester(s), compiled UNCHANGED with your files (do not return them):\n\n");
            m.testerFiles().forEach((n, c) -> sb.append("<<<TESTER ").append(n).append('\n').append(c).append("\nTESTER>>>\n\n"));
        }
        if (m.homework().mainClass() != null) {
            sb.append("The grader runs: javac *.java && java ").append(m.homework().mainClass()).append('\n');
        }
        if (feedback != null) {
            sb.append("\nNOTE: ").append(feedback).append('\n');
        }
        return sb.toString();
    }

    /** {estimated input tokens, estimated output tokens} for one first-attempt call (no model is called). */
    public static long[] estimate(AssignmentMaterials m) {
        long in = TokenEstimate.of(SolutionVerifier.resource(SYSTEM_RESOURCE) + SolutionVerifier.resource(SCHEMA_RESOURCE)
                + userPrompt(m, null));
        long starterChars = m.starterFiles().values().stream().mapToLong(String::length).sum();
        // a full solution is typically 1.5-2x the starter (or ~12k characters when there is none)
        long out = TokenEstimate.fromChars(Math.max(12_000, starterChars * 2) + 800);
        return new long[]{in, out};
    }

    private ObjectNode provenance(AssignmentMaterials m, String returnedModel, String notes, int attempts, long in,
                                  long out, SolutionVerifier.Result v, String error, boolean verified) {
        ObjectNode p = JSON.createObjectNode();
        p.put("homework", m.homework().name());
        p.put("kind", "AI-generated reference solution");
        p.put("created_utc", Instant.now().toString());
        p.put("model_provider", adapter.provider());
        p.put("model_id", adapter.modelId());
        p.put("model_returned", returnedModel);
        p.put("prompt_version", promptVersion());
        if (temperature == null) {
            p.put("temperature", "provider default (not sent)");
        } else {
            p.put("temperature", temperature);
        }
        p.put("max_output_tokens", maxOutputTokens);
        p.put("attempts", attempts);
        p.put("input_tokens", in);
        p.put("output_tokens", out);
        p.put("model_notes", notes);
        ObjectNode ver = p.putObject("automatic_verification");
        ver.put("verified", verified);
        if (v != null) {
            ver.put("compiled", v.compiled());
            ver.put("output_ok", v.outputOk());
            if (v.harnessOk() != null) {
                ver.put("independent_sort_check", v.harnessOk());
            }
            ver.put("detail", v.detail());
        }
        if (error != null) {
            ver.put("error", error);
        }
        p.put("human_reviewed", false);
        p.put("human_review_note", "Set to true only after a person has checked the code against the assignment; "
                + "the spec requires independent checking before a reference is used as a key.");
        ArrayNode testers = p.putArray("compiled_with_supplied_testers");
        m.testerFiles().keySet().forEach(testers::add);
        return p;
    }

    private static void write(Path dir, Map<String, String> files, ObjectNode provenance) {
        try {
            if (Files.exists(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(java.util.Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
                }
            }
            Files.createDirectories(dir);
            for (Map.Entry<String, String> e : files.entrySet()) {
                Files.writeString(dir.resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
            }
            JSON.writeValue(dir.resolve("provenance.json").toFile(), provenance);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
