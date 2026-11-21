package aiaa.cam;

import aiaa.llm.ModelAdapter;
import aiaa.llm.ModelException;
import aiaa.llm.ModelRequest;
import aiaa.llm.ModelResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Calls ModelAdapter & parses JSON responses
 */
public class LLMGrader
{
    private static final ObjectMapper JSON = new ObjectMapper();

    public record MethodScore(String methodName, double score, String feedback) {}

    public record GradingOutcome(
            boolean success,
            double totalScore,
            String overallFeedback,
            List<MethodScore> methodScores,
            String provider,
            String requestedModel,
            String returnedModel,
            long inputTokens,
            long outputTokens,
            int retries,
            long latencyMs,
            double costUsd) {}

    private final CAMPromptLoader promptLoader;
    private final Map<String, Double> inputPricePer1k;
    private final Map<String, Double> outputPricePer1k;
    private final int maxOutputTokens;
    private final Double temperature;

    public LLMGrader(
            CAMPromptLoader promptLoader,
            Map<String, Double> inputPricePer1k,
            Map<String, Double> outputPricePer1k,
            int maxOutputTokens,
            Double temperature) {
        this.promptLoader = promptLoader;
        this.inputPricePer1k = inputPricePer1k;
        this.outputPricePer1k = outputPricePer1k;
        this.maxOutputTokens = maxOutputTokens;
        this.temperature = temperature;
    }

    public GradingOutcome gradeSubmission(ModelAdapter adapter, String assignment, String studentCode)
    {
        String userPrompt = promptLoader.buildUserPrompt(assignment, studentCode);

        ModelRequest request = new ModelRequest(
                promptLoader.getSystemPrompt(),
                userPrompt,
                maxOutputTokens,
                temperature,
                "cam_grading_schema",
                promptLoader.getGradingJsonSchema()
        );

        try
        {
            ModelResponse response = adapter.complete(request);
            JsonNode root = JSON.readTree(response.text());

            double rawScore = root.path("total_score").asDouble(0.0);
            double clampedScore = Math.min(100.0, Math.max(0.0, rawScore));
            String overallFeedback = root.path("overall_feedback").asText("");

            List<MethodScore> methods = new ArrayList<>();
            JsonNode methodArray = root.path("method_scores");

            if (methodArray.isArray())
            {
                for (JsonNode node : methodArray)
                {
                    methods.add(new MethodScore(
                            node.path("method_name").asText("unknown"),
                            node.path("score").asDouble(0.0),
                            node.path("feedback").asText("")
                    ));
                }
            }

            double cost = calculateCost(adapter.modelId(), response.inputTokens(), response.outputTokens());

            return new GradingOutcome(
                    true, clampedScore, overallFeedback, methods,
                    adapter.provider(), response.requestedModel(), response.returnedModel(),
                    response.inputTokens(), response.outputTokens(),
                    response.retries(), response.latencyMs(), cost
            );

        }
        catch (ModelException | IOException caught)
        {
            int retries = (caught instanceof ModelException me) ? me.retries() : 0;

            return new GradingOutcome(
                    false, 0.0, "Error: " + caught.getMessage(), List.of(),
                    adapter.provider(), adapter.modelId(), "FAILED",
                    0, 0, retries, 0, 0.0
            );
        }
    }

    private double calculateCost(String modelId, long inputTokens, long outputTokens)
    {
        double inRate = inputPricePer1k.getOrDefault(modelId, 0.0);
        double outRate = outputPricePer1k.getOrDefault(modelId, 0.0);
        return ((inputTokens / 1000.0) * inRate) + ((outputTokens / 1000.0) * outRate);
    }
}
