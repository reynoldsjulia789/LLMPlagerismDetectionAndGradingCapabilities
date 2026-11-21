package aiaa.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

/**
 * Anthropic Claude adapter (Messages API via the official Java SDK).
 *
 * <p>Credentials come from the environment ({@code ANTHROPIC_API_KEY}), never from the repo. The model ID
 * is always explicit so it is recorded with every result. Design choices for this research setting:
 * <ul>
 *   <li>Structured output: the request's JSON schema is sent as {@code output_config.format}.</li>
 *   <li>Retries are done here (SDK retries off) so every retry is counted, as the spec requires.</li>
 *   <li>No automatic model fallback on refusals: a refused call is reported as a failure, so every
 *       result is attributable to exactly one model.</li>
 *   <li>Current Claude models think adaptively and reject sampling parameters, so a requested
 *       temperature is reported as an unsupported setting instead of being sent. Effort is configurable;
 *       when not given, the model's own default applies.</li>
 *   <li>Output truncated by {@code max_tokens} is an error, never accepted silently.</li>
 * </ul>
 */
public final class AnthropicAdapter implements ModelAdapter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AnthropicClient client;
    private final String modelId;
    private final String effort;
    private final int maxRetries;

    /** @param effort low / medium / high / xhigh / max, or null for the model default */
    public AnthropicAdapter(AnthropicClient client, String modelId, String effort, int maxRetries) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("An explicit Claude model ID is required (--model), e.g. claude-opus-5-5");
        }
        this.client = client;
        this.modelId = modelId;
        this.effort = effort;
        this.maxRetries = maxRetries;
    }

    /** Builds an adapter from {@code ANTHROPIC_API_KEY} (and optional {@code ANTHROPIC_BASE_URL}). */
    public static AnthropicAdapter fromEnvironment(String modelId, String effort, int maxRetries) {
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("ANTHROPIC_API_KEY is not set (see the README section 'Model access (Claude)')");
        }
        AnthropicClient client = AnthropicOkHttpClient.builder().fromEnv().maxRetries(0).build();
        return new AnthropicAdapter(client, modelId, effort, maxRetries);
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public String modelId() {
        return modelId;
    }

    public String effort() {
        return effort;
    }

    @Override
    public ModelResponse complete(ModelRequest request) throws ModelException {
        if (request.temperature() != null) {
            throw new ModelException("temperature is not supported by current Claude models (" + modelId
                    + "); omit --temperature. This is recorded as an unsupported setting.", 0);
        }
        MessageCreateParams params = buildParams(request);
        int attempt = 0;
        while (true) {
            long start = System.nanoTime();
            try {
                Message m = client.messages().create(params);
                long latencyMs = (System.nanoTime() - start) / 1_000_000;
                return toResponse(m, latencyMs, attempt);
            } catch (RateLimitException | InternalServerException | AnthropicIoException e) {
                if (attempt >= maxRetries) {
                    throw new ModelException("Claude request failed after " + attempt + " retries: " + e.getMessage(),
                            attempt, e);
                }
                backoff(attempt);
            } catch (AnthropicServiceException e) {
                // 400 / 401 / 403 / 404 / 413 ...: retrying will not help
                String type = e.errorType().map(Object::toString).orElse("HTTP " + e.statusCode());
                throw new ModelException("Claude API error (" + type + "): " + e.getMessage(), attempt, e);
            }
            attempt++;
        }
    }

    private MessageCreateParams buildParams(ModelRequest request) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(modelId)
                .maxTokens(request.maxOutputTokens() == null ? 16_000L : request.maxOutputTokens())
                .system(request.systemPrompt())
                .addUserMessage(request.userPrompt());
        OutputConfig.Builder oc = OutputConfig.builder();
        boolean any = false;
        if (effort != null) {
            oc.effort(OutputConfig.Effort.of(effort));
            any = true;
        }
        if (request.wantsJson()) {
            oc.format(JsonOutputFormat.builder().schema(schema(request.jsonSchema())).build());
            any = true;
        }
        if (any) {
            b.outputConfig(oc.build());
        }
        return b.build();
    }

    private static JsonOutputFormat.Schema schema(String jsonSchema) {
        try {
            Map<String, Object> map = JSON.readValue(jsonSchema, new TypeReference<>() {
            });
            JsonOutputFormat.Schema.Builder sb = JsonOutputFormat.Schema.builder();
            map.forEach((k, v) -> sb.putAdditionalProperty(k, JsonValue.from(v)));
            return sb.build();
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON schema in request", e);
        }
    }

    private ModelResponse toResponse(Message m, long latencyMs, int retries) throws ModelException {
        StopReason stop = m.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stop)) {
            String detail = m.stopDetails().map(d -> d.category().map(Object::toString).orElse("uncategorized") + ": "
                    + d.explanation().orElse("")).orElse("no details");
            throw new ModelException("Model refused (" + detail + ")", retries);
        }
        if (StopReason.MAX_TOKENS.equals(stop)) {
            throw new ModelException("Output truncated by max_tokens; raise --max-output-tokens", retries);
        }
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : m.content()) {
            block.text().ifPresent(t -> text.append(t.text()));
        }
        return new ModelResponse(text.toString(), modelId, m.model().asString(), m.usage().inputTokens(),
                m.usage().outputTokens(), latencyMs, retries);
    }

    private static void backoff(int attempt) throws ModelException {
        try {
            Thread.sleep(Math.min(60_000L, (long) (1000 * Math.pow(2, attempt))));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException("Interrupted during retry backoff", attempt, e);
        }
    }
}
