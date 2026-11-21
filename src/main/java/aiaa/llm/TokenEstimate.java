package aiaa.llm;

/**
 * Rough token estimates for dry runs and budget planning, before a model has reported real usage.
 * Source code tokenizes at roughly 3-4 characters per token with current OpenAI tokenizers; 3.5 is
 * used so estimates err slightly high. Every estimate produced this way must be labeled as an estimate.
 */
public final class TokenEstimate {

    public static final double CHARS_PER_TOKEN = 3.5;

    private TokenEstimate() {
    }

    public static long fromChars(long chars) {
        return (long) Math.ceil(chars / CHARS_PER_TOKEN);
    }

    public static long of(String text) {
        return fromChars(text.length());
    }
}
