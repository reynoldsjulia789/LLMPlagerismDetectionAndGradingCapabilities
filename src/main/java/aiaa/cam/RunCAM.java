package aiaa.cam;

import aiaa.llm.AnthropicAdapter;
import aiaa.llm.GeminiAdapter;
import aiaa.llm.MockAdapterCAM;
import aiaa.llm.ModelAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RunCAM
{

    public static void main(String[] args)
    {
        boolean useLiveModels = false;
        int     limit         = -1;

        for (int idx = 0; idx < args.length; idx++)
        {
            if ("--live".equalsIgnoreCase(args[idx]))
            {
                useLiveModels = true;
                continue;
            }
            if ("--limit".equalsIgnoreCase(args[idx]) && idx + 1 < args.length)
            {
                limit = Integer.parseInt(args[++idx]);
            }
        }

        try
        {
            List<ModelAdapter> adapters = new ArrayList<>();

            if (useLiveModels)
            {
                System.out.println("Mode: LIVE (using real LLM APIs)");

                adapters.add(GeminiAdapter.fromEnvironment("gemini-3.1-flash-lite-preview", 3));
                adapters.add(AnthropicAdapter.fromEnvironment("claude-haiku-5-5", null, 1));
            }
            else
            {
                System.out.println("Mode: MOCK (dry-run using simulated LLM responses; JUnit tests still run live)");
                adapters.add(new MockAdapterCAM("openai", "gpt-4o-mini", 101L));
                adapters.add(new MockAdapterCAM("google", "gemma-2-27b", 202L));
                adapters.add(new MockAdapterCAM("anthropic", "claude-3-5-sonnet", 303L));
            }

            // Pricing per 1,000 tokens (USD) for cost estimation
            Map<String, Double> inputPricePer1k = Map.of(
                    "gpt-4o-mini", 0.00015,
                    "claude-haiku-5-5", 0.001,
                    "gemini-3.1-flash-lite-preview", 0.0
            );
            Map<String, Double> outputPricePer1k = Map.of(
                    "gpt-4o-mini", 0.00060,
                    "claude-haiku-5-5", 0.005,
                    "gemini-3.1-flash-lite-preview", 0.0
            );

            // Run JUnit tests and LLM grading
            CodeAssessmentModule cam = new CodeAssessmentModule(adapters, inputPricePer1k, outputPricePer1k);
            cam.setMaxSubmissions(limit);
            cam.runFullAssessment();

            // Generate CSVs
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
