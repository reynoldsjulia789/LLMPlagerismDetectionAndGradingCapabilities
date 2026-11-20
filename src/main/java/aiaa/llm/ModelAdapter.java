package aiaa.llm;

/**
 * Common inference interface shared by every AIAA module (PDM, CAM, ...).
 * One implementation per provider; callers never talk to a provider API directly,
 * so the same prompts / parsing / logging work for any model family.
 */
public interface ModelAdapter {

    /** Provider label recorded in logs, e.g. "openai", "mock". */
    String provider();

    /** Exact model ID as requested (the response may report a more specific version). */
    String modelId();

    /**
     * Runs one inference call. Implementations handle their own retries and must
     * report how many were used in {@link ModelResponse#retries()}.
     *
     * @throws ModelException when the call ultimately fails or the output is unusable
     *                        (e.g. truncated by the token limit, refused).
     */
    ModelResponse complete(ModelRequest request) throws ModelException;
}
