package aiaa.synth;

import aiaa.llm.ModelAdapter;
import aiaa.llm.ModelException;
import aiaa.llm.ModelRequest;
import aiaa.llm.ModelResponse;
import aiaa.pdm.JavaLexer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Equivalent control flow" variants: asks an LLM to restructure loops/conditionals without
 * changing behavior or names. The result must keep the same file set, actually change the
 * code, and compile together with the untouched files; otherwise it is retried with the
 * compiler errors, then rejected.
 */
final class ControlFlowRewriter {

    static final String SYSTEM_RESOURCE = "prompts/control_flow_rewrite_system_v1.txt";
    static final String SCHEMA_RESOURCE = "prompts/control_flow_rewrite_schema_v1.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    record Outcome(Map<String, String> files, boolean ok, int attempts, String changes, String error,
                   long inputTokens, long outputTokens, String modelReturned) {
    }

    private final ModelAdapter adapter;
    private final Integer maxOutputTokens;
    private final Double temperature;
    private final int maxAttempts;
    private final String system = resource(SYSTEM_RESOURCE);
    private final String schema = resource(SCHEMA_RESOURCE);

    ControlFlowRewriter(ModelAdapter adapter, Integer maxOutputTokens, Double temperature, int maxAttempts) {
        this.adapter = adapter;
        this.maxOutputTokens = maxOutputTokens;
        this.temperature = temperature;
        this.maxAttempts = maxAttempts;
    }

    String promptVersion() {
        return "control_flow_rewrite_v1@" + sha256(system + "\u0000" + schema).substring(0, 10);
    }

    String modelId() {
        return adapter.modelId();
    }

    /**
     * @param targets  files to restructure (implementation files)
     * @param fixed    files sent for context only and kept unchanged (e.g. testers)
     */
    Outcome rewrite(Map<String, String> targets, Map<String, String> fixed) {
        long in = 0;
        long out = 0;
        String lastError = null;
        String returned = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String prompt = userPrompt(targets, fixed, lastError);
            try {
                ModelResponse r = adapter.complete(new ModelRequest(system, prompt, maxOutputTokens, temperature,
                        "control_flow_rewrite", schema));
                in += r.inputTokens();
                out += r.outputTokens();
                returned = r.returnedModel();
                Parsed p = parse(r.text(), targets.keySet().stream().toList());
                if (sameCode(p.files(), targets)) {
                    lastError = "The rewrite did not change any code. Restructure the control flow as instructed.";
                    continue;
                }
                Map<String, String> all = new LinkedHashMap<>(p.files());
                all.putAll(fixed);
                CompileCheck.Result c = CompileCheck.compile(all);
                if (c.ok()) {
                    return new Outcome(p.files(), true, attempt, p.changes(), null, in, out, returned);
                }
                lastError = "Your previous rewrite did not compile:\n" + c.diagnostics();
            } catch (ModelException e) {
                lastError = null;
                if (attempt == maxAttempts) {
                    return new Outcome(Map.of(), false, attempt, "", "model error: " + e.getMessage(), in, out, returned);
                }
            } catch (IllegalArgumentException e) {
                lastError = "Your previous response was invalid: " + e.getMessage();
            }
        }
        return new Outcome(Map.of(), false, maxAttempts, "", lastError, in, out, returned);
    }

    /** {estimated input tokens, estimated output tokens} for one first-attempt call (no model is called). */
    static long[] estimate(Map<String, String> targets, Map<String, String> fixed) {
        String system = resource(SYSTEM_RESOURCE) + resource(SCHEMA_RESOURCE);
        long in = aiaa.llm.TokenEstimate.of(system + userPrompt(targets, fixed, null));
        long code = targets.values().stream().mapToLong(String::length).sum();
        long out = aiaa.llm.TokenEstimate.fromChars(code + 600); // whole files come back, plus JSON and changes
        return new long[]{in, out};
    }

    private static String userPrompt(Map<String, String> targets, Map<String, String> fixed, String lastError) {
        StringBuilder sb = new StringBuilder();
        sb.append("Rewrite these files with equivalent control flow:\n\n");
        targets.forEach((name, content) -> sb.append("<<<FILE ").append(name).append('\n')
                .append(content).append("\nFILE>>>\n\n"));
        if (!fixed.isEmpty()) {
            sb.append("For context only (do NOT return these; they will be compiled with your files unchanged):\n\n");
            fixed.forEach((name, content) -> sb.append("<<<CONTEXT ").append(name).append('\n')
                    .append(content).append("\nCONTEXT>>>\n\n"));
        }
        if (lastError != null) {
            sb.append("NOTE: ").append(lastError).append('\n');
        }
        return sb.toString();
    }

    record Parsed(Map<String, String> files, String changes) {
    }

    static Parsed parse(String text, List<String> expectedNames) {
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (IOException e) {
            throw new IllegalArgumentException("not valid JSON");
        }
        Map<String, String> files = new LinkedHashMap<>();
        for (JsonNode f : root.path("files")) {
            files.put(f.path("name").asText(), f.path("content").asText());
        }
        if (!files.keySet().equals(new java.util.HashSet<>(expectedNames))) {
            throw new IllegalArgumentException("expected files " + expectedNames + " but got " + files.keySet());
        }
        Map<String, String> ordered = new LinkedHashMap<>();
        expectedNames.forEach(n -> ordered.put(n, files.get(n)));
        List<String> changes = new ArrayList<>();
        root.path("changes").forEach(c -> changes.add(c.asText()));
        return new Parsed(ordered, String.join("; ", changes));
    }

    private static boolean sameCode(Map<String, String> a, Map<String, String> b) {
        for (String k : b.keySet()) {
            List<String> x = JavaLexer.significant(JavaLexer.lex(a.get(k))).stream().map(JavaLexer.Token::text).toList();
            List<String> y = JavaLexer.significant(JavaLexer.lex(b.get(k))).stream().map(JavaLexer.Token::text).toList();
            if (!x.equals(y)) {
                return false;
            }
        }
        return true;
    }

    private static String resource(String name) {
        try (InputStream in = ControlFlowRewriter.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
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
