package aiaa.llm;

/** A model call that failed after all retries, or returned unusable output. */
public class ModelException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int retries;

    public ModelException(String message, int retries) {
        super(message);
        this.retries = retries;
    }

    public ModelException(String message, int retries, Throwable cause) {
        super(message, cause);
        this.retries = retries;
    }

    public int retries() {
        return retries;
    }
}
