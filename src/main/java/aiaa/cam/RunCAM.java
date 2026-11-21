package aiaa.cam;

import aiaa.llm.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RunCAM
{
    private static final String _google_model    = "gemini-3.5-flash-lite";
    private static final String _groq_model      = "openai/gpt-oss-120b";
    private static final String _anthropic_model = "claude-haiku-5-5";

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

                adapters.add(GoogleAdapter.fromEnvironment(_google_model, 2));
                adapters.add(GroqAdapter.fromEnvironment(_groq_model, 2));
//                adapters.add(AnthropicAdapter.fromEnvironment("claude-haiku-5-5", null, 1));
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
                    _groq_model, 0.00015,               // using free tier, but recording cost for paid version
                    _anthropic_model, 0.001,
                    _google_model, 0.0003                   // using free tier, but recording cost for paid version
            );
            Map<String, Double> outputPricePer1k = Map.of(
                    _groq_model, 0.00060,               // using free tier, but recording cost for paid version
                    _anthropic_model, 0.005,
                    _google_model, 0.0025                   // using free tier, but recording cost for paid version
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
