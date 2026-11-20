package aiaa.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * OpenAI Chat Completions adapter.
 *
 * <p>Configuration comes from the environment so no credentials live in the repo:
 * <ul>
 *   <li>{@code OPENAI_API_KEY} (required)</li>
 *   <li>{@code OPENAI_BASE_URL} (optional, default {@code https://api.openai.com/v1})</li>
 * </ul>
 * The model ID is always passed explicitly so it gets recorded with every result.
 */
public final class OpenAiAdapter implements ModelAdapter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String modelId;
    private final String apiKey;
    private final URI endpoint;
    private final int maxRetries;
    private final HttpClient http;

    public OpenAiAdapter(String modelId, String apiKey, String baseUrl, int maxRetries) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("An explicit OpenAI model ID is required (--model)");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("OPENAI_API_KEY is not set");
        }
        this.modelId = modelId;
        this.apiKey = apiKey;
        String base = (baseUrl == null || baseUrl.isBlank()) ? "https://api.openai.com/v1" : baseUrl;
        this.endpoint = URI.create(base.replaceAll("/+$", "") + "/chat/completions");
        this.maxRetries = maxRetries;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    /** Builds an adapter from {@code OPENAI_API_KEY} / {@code OPENAI_BASE_URL}. */
    public static OpenAiAdapter fromEnvironment(String modelId, int maxRetries) {
        return new OpenAiAdapter(modelId, System.getenv("OPENAI_API_KEY"),
                System.getenv("OPENAI_BASE_URL"), maxRetries);
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public ModelResponse complete(ModelRequest request) throws ModelException {
        String body = buildBody(request);
        int attempt = 0;
        while (true) {
            long start = System.nanoTime();
            try {
                HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofMinutes(5))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                long latencyMs = (System.nanoTime() - start) / 1_000_000;
                int status = response.statusCode();
                if (status == 200) {
                    return parse(response.body(), latencyMs, attempt);
                }
                boolean retryable = status == 429 || status >= 500;
                if (!retryable || attempt >= maxRetries) {
                    throw new ModelException("OpenAI HTTP " + status + ": " + abbreviate(response.body()), attempt);
                }
                sleepBeforeRetry(attempt, response.headers().firstValue("retry-after"));
            } catch (IOException e) {
                if (attempt >= maxRetries) {
                    throw new ModelException("OpenAI request failed: " + e.getMessage(), attempt, e);
                }
                sleepBeforeRetry(attempt, Optional.empty());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ModelException("Interrupted", attempt, e);
            }
            attempt++;
        }
    }

    private String buildBody(ModelRequest request) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", modelId);
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.systemPrompt());
        messages.addObject().put("role", "user").put("content", request.userPrompt());
        if (request.maxOutputTokens() != null) {
            root.put("max_completion_tokens", request.maxOutputTokens());
        }
        if (request.temperature() != null) {
            root.put("temperature", request.temperature());
        }
        if (request.wantsJson()) {
            try {
                ObjectNode format = root.putObject("response_format");
                format.put("type", "json_schema");
                ObjectNode schema = format.putObject("json_schema");
                schema.put("name", request.schemaName() == null ? "result" : request.schemaName());
                schema.put("strict", true);
                schema.set("schema", JSON.readTree(request.jsonSchema()));
            } catch (IOException e) {
                throw new IllegalArgumentException("Invalid JSON schema in request", e);
            }
        }
        return root.toString();
    }

    private ModelResponse parse(String body, long latencyMs, int retries) throws ModelException {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode choice = root.path("choices").path(0);
            String finish = choice.path("finish_reason").asText("");
            JsonNode message = choice.path("message");
            if (message.hasNonNull("refusal")) {
                throw new ModelException("Model refused: " + message.get("refusal").asText(), retries);
            }
            if ("length".equals(finish)) {
                // Never accept silently truncated output.
                throw new ModelException("Output truncated by max_completion_tokens", retries);
            }
            JsonNode usage = root.path("usage");
            return new ModelResponse(
                    message.path("content").asText(""),
                    modelId,
                    root.path("model").asText(modelId),
                    usage.path("prompt_tokens").asLong(0),
                    usage.path("completion_tokens").asLong(0),
                    latencyMs,
                    retries);
        } catch (IOException e) {
            throw new ModelException("Unparseable OpenAI response: " + abbreviate(body), retries, e);
        }
    }

    private static void sleepBeforeRetry(int attempt, Optional<String> retryAfter) throws ModelException {
        long millis = retryAfter.flatMap(OpenAiAdapter::parseSeconds)
                .map(s -> s * 1000)
                .orElse((long) (1000 * Math.pow(2, attempt)));
        try {
            Thread.sleep(Math.min(millis, 60_000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException("Interrupted during retry backoff", attempt, e);
        }
    }

    private static Optional<Long> parseSeconds(String value) {
        try {
            return Optional.of((long) Math.ceil(Double.parseDouble(value.trim())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "" : (s.length() > 500 ? s.substring(0, 500) + "..." : s);
    }
}
