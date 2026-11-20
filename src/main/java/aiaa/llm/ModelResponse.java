package aiaa.llm;

/**
 * Result of one inference call plus the bookkeeping the spec requires
 * (exact model version, tokens, latency, retries).
 *
 * @param text          raw model output (JSON text when a schema was requested)
 * @param requestedModel model ID that was requested
 * @param returnedModel  model/version string the provider reported, if any
 * @param inputTokens    prompt tokens billed
 * @param outputTokens   completion tokens billed
 * @param latencyMs      wall-clock time of the successful attempt, in milliseconds
 * @param retries        number of retried attempts before success
 */
public record ModelResponse(
        String text,
        String requestedModel,
        String returnedModel,
        long inputTokens,
        long outputTokens,
        long latencyMs,
        int retries) {
}
