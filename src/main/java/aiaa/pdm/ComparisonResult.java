package aiaa.pdm;

/**
 * One similarity measurement between two pieces of code: a peer pair (A,B both students,
 * A &lt; B) or a student vs. AI-reference comparison (A = student, B = reference).
 * Score fields are null when the measurement failed; see {@code status}/{@code error}.
 */
public record ComparisonResult(
        Comparison comparison,
        Method method,
        String subA,
        String subB,
        Double similarity,
        Integer structure,
        Integer syntax,
        Integer tokens,
        Integer logic,
        Double containment,
        String evidence,
        String rationale,
        Status status,
        String error,
        String modelProvider,
        String modelId,
        String modelReturned,
        String promptVersion,
        long inputTokens,
        long outputTokens,
        long latencyMs,
        int retries,
        String rawResponse) {

    public enum Comparison { PEER, AI_REFERENCE }

    public enum Method { LLM, TOKEN }

    public enum Status {
        OK,
        /** Input exceeded the configured size limit; not sent (code is never silently truncated). */
        TOO_LARGE,
        /** The model call failed after retries, or was refused/truncated. */
        MODEL_ERROR,
        /** The model answered but the output did not satisfy the schema/ranges. */
        PARSE_ERROR
    }

    public boolean ok() {
        return status == Status.OK;
    }

    /** Stable identity used for resuming interrupted runs. */
    public String key() {
        return key(comparison, subA, subB);
    }

    public static String key(Comparison c, String a, String b) {
        return c + "|" + a + "|" + b;
    }
}
