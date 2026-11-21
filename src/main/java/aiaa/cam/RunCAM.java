package aiaa.cam;

import aiaa.llm.ModelAdapter;
import aiaa.llm.OpenAiAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RunCAM
{
    public static void main(String[] args)
    {
        try
        {
            // 1. Configure your LLM adapters (requires OPENAI_API_KEY env variable)
            List<ModelAdapter> adapters = new ArrayList<>();

            adapters.add(OpenAiAdapter.fromEnvironment("gpt-4o-mini", 3));

            // Once your Gemma and Claude adapters are ready in aiaa.llm, add them here:
            // adapters.add(new BedrockGemmaAdapter("google.gemma-...", 3));
            // adapters.add(new BedrockClaudeAdapter("anthropic.claude-...", 3));

            // 2. Define pricing per 1,000 tokens (USD) for cost estimation
            Map<String, Double> inputPricePer1k = Map.of(
                    "gpt-4o-mini", 0.00015,
                    "gpt-4o", 0.00250
            );
            Map<String, Double> outputPricePer1k = Map.of(
                    "gpt-4o-mini", 0.00060,
                    "gpt-4o", 0.01000
            );

            // 3. Run JUnit tests & LLM grading across all submissions
            System.out.println("Starting Code Assessment Module...");
            CodeAssessmentModule cam = new CodeAssessmentModule(adapters, inputPricePer1k, outputPricePer1k);
            cam.runFullAssessment();

            // 4. Calculate MAE, RMSE, and efficiency summaries
            System.out.println("Generating statistics and chart CSVs...");
            CAMStatistics.generateAllReports();

            System.out.println("Code Assessment Module has finished running! Check the 'assessment_results/' folder.");

        }
        catch (Exception caught)
        {
            System.err.println("Assessment failed: " + caught.getMessage());
            caught.printStackTrace();
        }
    }
}
