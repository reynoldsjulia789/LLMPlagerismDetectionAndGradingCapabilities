package aiaa.pdm;

import aiaa.llm.ModelAdapter;
import aiaa.llm.ModelException;
import aiaa.llm.ModelRequest;
import aiaa.llm.ModelResponse;
import aiaa.pdm.ComparisonResult.Comparison;
import aiaa.pdm.ComparisonResult.Method;
import aiaa.pdm.ComparisonResult.Status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Asks an LLM to score the similarity of two code views, using the versioned prompts. */
public final class LlmSimilarityJudge {

    static final String SYSTEM_RESOURCE = "prompts/similarity_system_v1.txt";
    static final String PEER_RESOURCE = "prompts/peer_similarity_v1.txt";
    static final String AI_RESOURCE = "prompts/ai_reference_similarity_v1.txt";
    static final String SCHEMA_RESOURCE = "prompts/similarity_schema_v1.json";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private static final String NOTE_STRIP = "Instructor-provided starter code lines and supplied tester files have been "
            + "REMOVED from both submissions before this comparison (gaps in line numbers). The remaining code is "
            + "student-written or student-modified.";
    private static final String NOTE_KEEP = "Submissions are shown in full, as submitted.";

    private final ModelAdapter adapter;
    private final String assignment;
    private final StarterMode starterMode;
    private final Integer maxOutputTokens;
    private final Double temperature;
    private final int maxInputChars;

    private final String systemPrompt = resource(SYSTEM_RESOURCE);
    private final String peerTemplate = resource(PEER_RESOURCE);
    private final String aiTemplate = resource(AI_RESOURCE);
    private final String schema = resource(SCHEMA_RESOURCE);

    public LlmSimilarityJudge(ModelAdapter adapter, String assignment, StarterMode starterMode,
                              Integer maxOutputTokens, Double temperature, int maxInputChars) {
        this.adapter = adapter;
        this.assignment = assignment;
        this.starterMode = starterMode;
        this.maxOutputTokens = maxOutputTokens;
        this.temperature = temperature;
        this.maxInputChars = maxInputChars;
    }

    /**
     * Prompt version recorded with every result: template name plus a short hash of the
     * system prompt, template and schema, so any edit to a frozen prompt is detectable.
     */
    public String promptVersion(Comparison comparison) {
        String template = comparison == Comparison.PEER ? peerTemplate : aiTemplate;
        String name = comparison == Comparison.PEER ? "peer_similarity_v1" : "ai_reference_similarity_v1";
        return name + "@" + sha256(systemPrompt + "\u0000" + template + "\u0000" + schema).substring(0, 10);
    }

    public String buildUserPrompt(Comparison comparison, SourceView a, SourceView b) {
        String template = comparison == Comparison.PEER ? peerTemplate : aiTemplate;
        Map<String, String> values = Map.of(
                "ASSIGNMENT", assignment,
                "STARTER_NOTE", starterMode == StarterMode.STRIP ? NOTE_STRIP : NOTE_KEEP,
                "CODE_A", a.numberedText().stripTrailing(),
                "CODE_B", b.numberedText().stripTrailing());
        // Single pass so placeholder-like text inside student code is never substituted.
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public ComparisonResult judge(Comparison comparison, SourceView a, SourceView b) {
        String version = promptVersion(comparison);
        String userPrompt = buildUserPrompt(comparison, a, b);
        int size = systemPrompt.length() + userPrompt.length();
        if (size > maxInputChars) {
            return failure(comparison, a, b, version, Status.TOO_LARGE,
                    "Prompt is " + size + " chars, limit " + maxInputChars + " (code is never truncated)", null, 0);
        }
        ModelRequest request = new ModelRequest(systemPrompt, userPrompt, maxOutputTokens, temperature,
                "code_similarity", schema);
        ModelResponse response;
        try {
            response = adapter.complete(request);
        } catch (ModelException e) {
            return failure(comparison, a, b, version, Status.MODEL_ERROR, e.getMessage(), null, e.retries());
        }
        return fromResponse(comparison, a.id(), b.id(), version, response);
    }

    /** Converts a raw model response into a result (also used when resuming from saved responses). */
    ComparisonResult fromResponse(Comparison comparison, String idA, String idB, String version, ModelResponse response) {
        try {
            Parsed p = parse(response.text());
            return new ComparisonResult(comparison, Method.LLM, idA, idB, (double) p.similarity,
                    p.structure, p.syntax, p.tokens, p.logic, null, p.evidence, p.rationale, Status.OK, null,
                    adapter.provider(), adapter.modelId(), response.returnedModel(), version,
                    response.inputTokens(), response.outputTokens(), response.latencyMs(), response.retries(),
                    response.text());
        } catch (IllegalArgumentException e) {
            return new ComparisonResult(comparison, Method.LLM, idA, idB, null, null, null, null, null, null,
                    null, null, Status.PARSE_ERROR, e.getMessage(), adapter.provider(), adapter.modelId(),
                    response.returnedModel(), version, response.inputTokens(), response.outputTokens(),
                    response.latencyMs(), response.retries(), response.text());
        }
    }

    private ComparisonResult failure(Comparison comparison, SourceView a, SourceView b, String version,
                                     Status status, String error, String raw, int retries) {
        return new ComparisonResult(comparison, Method.LLM, a.id(), b.id(), null, null, null, null, null, null,
                null, null, status, error, adapter.provider(), adapter.modelId(), null, version,
                0, 0, 0, retries, raw);
    }

    record Parsed(int similarity, int structure, int syntax, int tokens, int logic, String evidence, String rationale) {
    }

    /** Parses and validates the model's JSON; throws IllegalArgumentException when unusable. */
    static Parsed parse(String text) {
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (IOException e) {
            throw new IllegalArgumentException("Response is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Response is not a JSON object");
        }
        StringBuilder evidence = new StringBuilder();
        for (JsonNode r : root.path("copied_regions")) {
            if (!evidence.isEmpty()) {
                evidence.append("; ");
            }
            evidence.append("A ").append(r.path("a").asText()).append(" ~ B ").append(r.path("b").asText());
            String d = r.path("description").asText("");
            if (!d.isBlank()) {
                evidence.append(" (").append(d).append(')');
            }
        }
        return new Parsed(score(root, "similarity"), score(root, "structure"), score(root, "syntax"),
                score(root, "tokens"), score(root, "logic"), evidence.toString(), root.path("rationale").asText(""));
    }

    private static int score(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || !n.isNumber()) {
            throw new IllegalArgumentException("Missing numeric field '" + field + "'");
        }
        double v = n.asDouble();
        if (v < 0 || v > 100 || v != Math.rint(v)) {
            throw new IllegalArgumentException("Field '" + field + "' out of range 0-100: " + v);
        }
        return (int) v;
    }

    /**
     * Offline responder for {@link aiaa.llm.MockAdapter}: answers with the token-baseline score
     * so the full pipeline can be exercised without API access. NOT a research result.
     */
    public static Function<ModelRequest, String> mockResponder() {
        Pattern a = Pattern.compile("<<<SUBMISSION_A\\n(.*?)\\nSUBMISSION_A>>>", Pattern.DOTALL);
        Pattern b = Pattern.compile("<<<SUBMISSION_B\\n(.*?)\\nSUBMISSION_B>>>", Pattern.DOTALL);
        return request -> {
            String codeA = stripNumbering(extract(a, request.userPrompt()));
            String codeB = stripNumbering(extract(b, request.userPrompt()));
            int s = (int) Math.round(TokenSimilarity.compare(codeA, codeB).jaccard());
            return JSON.createObjectNode()
                    .put("rationale", "MOCK: token Jaccard baseline, no model was called")
                    .put("structure", s).put("syntax", s).put("tokens", s).put("logic", s).put("similarity", s)
                    .set("copied_regions", JSON.createArrayNode()).toString();
        };
    }

    private static String extract(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : "";
    }

    private static String stripNumbering(String numbered) {
        return numbered.replaceAll("(?m)^=== File: .* ===$", "").replaceAll("(?m)^\\s*\\d+\\| ?", "");
    }

    static String resource(String name) {
        try (InputStream in = LlmSimilarityJudge.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
