package aiaa.llm;

/**
 * A single provider-neutral inference request.
 *
 * @param systemPrompt    instructions for the model
 * @param userPrompt      the task input (code, rubric, ...)
 * @param maxOutputTokens output token cap, or null for the provider default
 * @param temperature     sampling temperature, or null to omit (some models do not support it)
 * @param schemaName      name of the structured-output schema, or null for free text
 * @param jsonSchema      JSON Schema text the response must follow, or null for free text
 */
public record ModelRequest(
        String systemPrompt,
        String userPrompt,
        Integer maxOutputTokens,
        Double temperature,
        String schemaName,
        String jsonSchema) {

    public boolean wantsJson() {
        return jsonSchema != null;
    }
}
