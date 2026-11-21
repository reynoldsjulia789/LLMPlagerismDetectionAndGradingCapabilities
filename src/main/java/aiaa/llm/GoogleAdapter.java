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
 * Google Gemini / Gemma adapter (Gemini Developer API via REST).
 *
 * <p>Credentials come from the environment ({@code GEMINI_API_KEY}), never from the repo:
 * <ul>
 *   <li>{@code GEMINI_API_KEY} (required)</li>
 *   <li>{@code GEMINI_BASE_URL} (optional, default {@code https://googleapis.com})</li>
 * </ul>
 * The model ID is always passed explicitly (e.g. {@code gemini-2.5-flash} or {@code gemma-3-27b-it})
 * so it gets recorded with every result.
 */
public final class GoogleAdapter implements ModelAdapter
{

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String modelId;
    private final String apiKey;
    private final URI endpoint;
    private final int maxRetries;
    private final HttpClient http;

    public GoogleAdapter(String modelId, String apiKey, String baseUrl, int maxRetries)
    {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("An explicit Gemini/Gemma model ID is required (e.g. gemini-3.1-flash-lite-preview)");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("GEMINI_API_KEY is not set");
        }
        this.modelId = modelId;
        this.apiKey = apiKey;
        String base = (baseUrl == null || baseUrl.isBlank())
                ? "https://generativelanguage.googleapis.com"
                : baseUrl.replaceAll("/+$", "");
        this.endpoint = URI.create(base + "/v1beta/models/" + modelId + ":generateContent");
        System.out.println("Gemini Endpoint URL: " + this.endpoint);
        this.maxRetries = maxRetries;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    /** Builds an adapter from {@code GEMINI_API_KEY} (and optional {@code GEMINI_BASE_URL}). */
    public static GoogleAdapter fromEnvironment(String modelId, int maxRetries)
    {
        return new GoogleAdapter(
                modelId,
                System.getenv("GEMINI_API_KEY"),
                System.getenv("GEMINI_BASE_URL"),
                maxRetries
        );
    }

    @Override
    public String provider()
    {
        return "google";
    }

    @Override
    public String modelId()
    {
        return modelId;
    }

    @Override
    public ModelResponse complete(ModelRequest request) throws ModelException
    {
        String body = buildBody(request);
        int attempt = 0;

        while (true)
        {
            long start = System.nanoTime();

            try
            {
                HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofMinutes(5))
                        .header("x-goog-api-key", apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());

                long latencyMs = (System.nanoTime() - start) / 1_000_000;

                int status = response.statusCode();

                if (status == 200)
                {
                    return parse(response.body(), latencyMs, attempt);
                }

                boolean retryable = status == 429 || status >= 500;

                if (!retryable || attempt >= maxRetries)
                {
                    throw new ModelException("Gemini HTTP " + status + ": " + abbreviate(response.body()), attempt);
                }
                sleepBeforeRetry(attempt, response.headers().firstValue("retry-after"));

            }
            catch (IOException e)
            {
                if (attempt >= maxRetries)
                {
                    throw new ModelException("Gemini request failed: " + e.getMessage(), attempt, e);
                }

                sleepBeforeRetry(attempt, Optional.empty());
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new ModelException("Interrupted", attempt, e);
            }
            attempt++;
        }
    }

    private String buildBody(ModelRequest request)
    {
        ObjectNode root = JSON.createObjectNode();

        // System instruction
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank())
        {
            ObjectNode sysInst = root.putObject("systemInstruction");
            ArrayNode sysParts = sysInst.putArray("parts");
            sysParts.addObject().put("text", request.systemPrompt());
        }

        // User prompt
        ArrayNode contents = root.putArray("contents");
        ObjectNode userContent = contents.addObject();
        userContent.put("role", "user");
        ArrayNode userParts = userContent.putArray("parts");
        userParts.addObject().put("text", request.userPrompt());

        // Generation configuration (temperature, max tokens, and strict JSON schema)
        ObjectNode genConfig = root.putObject("generationConfig");
        boolean hasConfig = false;

        if (request.maxOutputTokens() != null)
        {
            genConfig.put("maxOutputTokens", request.maxOutputTokens());
            hasConfig = true;
        }
        if (request.temperature() != null)
        {
            genConfig.put("temperature", request.temperature());
            hasConfig = true;
        }
        if (request.wantsJson())
        {
            try
            {
                JsonNode schemaNode = JSON.readTree(request.jsonSchema());
                stripUnsupportedSchemaFields(schemaNode);
                genConfig.put("responseMimeType", "application/json");
                genConfig.set("responseSchema", schemaNode);
                hasConfig = true;
            }
            catch (IOException e)
            {
                throw new IllegalArgumentException("Invalid JSON schema in request", e);
            }
        }

        if (!hasConfig)
        {
            root.remove("generationConfig");
        }

        return root.toString();
    }

    private ModelResponse parse(String body, long latencyMs, int retries) throws ModelException
    {
        try
        {
            JsonNode root = JSON.readTree(body);

            // Check if prompt itself was blocked
            JsonNode promptFeedback = root.path("promptFeedback");
            if (promptFeedback.hasNonNull("blockReason"))
            {
                throw new ModelException("Gemini blocked prompt: " + promptFeedback.get("blockReason").asText(), retries);
            }

            JsonNode candidate = root.path("candidates").path(0);
            if (candidate.isMissingNode())
            {
                throw new ModelException("Gemini returned no candidates: " + abbreviate(body), retries);
            }

            String finishReason = candidate.path("finishReason").asText("");
            if ("MAX_TOKENS".equals(finishReason))
            {
                throw new ModelException("Output truncated by maxOutputTokens", retries);
            }
            if ("SAFETY".equals(finishReason) || "RECITATION".equals(finishReason) || "BLOCKLIST".equals(finishReason))
            {
                throw new ModelException("Gemini refused output (" + finishReason + ")", retries);
            }

            StringBuilder text = new StringBuilder();
            JsonNode parts = candidate.path("content").path("parts");
            if (parts.isArray())
            {
                for (JsonNode part : parts)
                {
                    if (part.hasNonNull("text"))
                    {
                        text.append(part.get("text").asText());
                    }
                }
            }

            JsonNode usage = root.path("usageMetadata");
            long inputTokens = usage.path("promptTokenCount").asLong(0);
            long outputTokens = usage.path("candidatesTokenCount").asLong(0);
            String returnedModel = root.path("modelVersion").asText(modelId);

            return new ModelResponse(
                    text.toString(),
                    modelId,
                    returnedModel,
                    inputTokens,
                    outputTokens,
                    latencyMs,
                    retries
            );

        }
        catch (IOException e)
        {
            throw new ModelException("Unparseable Gemini response: " + abbreviate(body), retries, e);
        }
    }

    private static void sleepBeforeRetry(int attempt, Optional<String> retryAfter) throws ModelException
    {
        long millis = retryAfter.flatMap(GoogleAdapter::parseSeconds)
                .map(s -> s * 1000)
                .orElse((long) (1000 * Math.pow(2, attempt)));
        try
        {
            Thread.sleep(Math.min(millis, 60_000));
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new ModelException("Interrupted during retry backoff", attempt, e);
        }
    }

    private static Optional<Long> parseSeconds(String value)
    {
        try
        {
            return Optional.of((long) Math.ceil(Double.parseDouble(value.trim())));
        }
        catch (NumberFormatException e)
        {
            return Optional.empty();
        }
    }

    private static String abbreviate(String s)
    {
        return s == null ? "" : (s.length() > 500 ? s.substring(0, 500) + "..." : s);
    }

    private static void stripUnsupportedSchemaFields(JsonNode node)
    {
        if (node.isObject())
        {
            ObjectNode obj = (ObjectNode) node;
            obj.remove("additionalProperties");
            obj.fields().forEachRemaining(entry -> stripUnsupportedSchemaFields(entry.getValue()));
        }
        else if (node.isArray())
        {
            for (JsonNode item : node)
            {
                stripUnsupportedSchemaFields(item);
            }
        }
    }
}
