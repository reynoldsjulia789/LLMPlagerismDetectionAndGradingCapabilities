package aiaa.cam;

import aiaa.llm.MockAdapterCAM;
import aiaa.llm.ModelAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RunCAM
{

    public static void main(String[] args)
    {
        boolean useLiveModels = true;

        for (String arg : args)
        {
            if ("--mock".equalsIgnoreCase(arg))
            {
                useLiveModels = false;
                break;
            }
        }

        try
        {
            List<ModelAdapter> llms = new ArrayList<>();

            if (useLiveModels)
            {
                System.out.println("Mode: LIVE (using real LLM APIs)");
//                llms.add(OpenAiAdapter.fromEnvironment("gpt-4o-mini", 3));

                // fix once LLM adapters are added
            }
            else
            {
                System.out.println("Mode: MOCK (dry-run using simulated LLM responses; JUnit tests still run live)");
                llms.add(new MockAdapterCAM("openai", "gpt-4o-mini", 101L));
                llms.add(new MockAdapterCAM("google", "gemma-2-27b", 202L));
                llms.add(new MockAdapterCAM("anthropic", "claude-3-5-sonnet", 303L));
            }

            // Pricing per 1,000 tokens (USD) for cost estimation
            Map<String, Double> inputPricePer1k = Map.of(
                    "gpt-4o-mini", 0.00015,
                    "gpt-4o", 0.00250,
                    "gemma-2-27b", 0.00010,
                    "claude-3-5-sonnet", 0.00300
            );
            Map<String, Double> outputPricePer1k = Map.of(
                    "gpt-4o-mini", 0.00060,
                    "gpt-4o", 0.01000,
                    "gemma-2-27b", 0.00020,
                    "claude-3-5-sonnet", 0.01500
            );

            // Run JUnit tests and LLM grading
            CodeAssessmentModule cam = new CodeAssessmentModule(llms, inputPricePer1k, outputPricePer1k);
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
