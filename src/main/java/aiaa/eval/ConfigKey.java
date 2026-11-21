package aiaa.eval;

/**
 * One detection configuration that gets its own threshold: thresholds are selected separately
 * per homework, starter mode, scoring method/model/prompt, comparison type and level.
 *
 * @param homework      Hw1 / Hw2 / Hw8
 * @param starterMode   STRIP or KEEP
 * @param method        TOKEN (non-LLM baseline) or LLM
 * @param modelId       exact model ID (or the token baseline's name)
 * @param promptVersion prompt version hash ("" for the token baseline)
 * @param comparison    PEER or AI_REFERENCE
 * @param level         PAIR or STUDENT
 */
public record ConfigKey(String homework, String starterMode, String method, String modelId, String promptVersion,
                        String comparison, String level) {

    public String id() {
        return String.join("/", homework, starterMode, method, modelId, comparison, level);
    }
}
