package aiaa.llm;

import java.util.Random;

/**
 * Offline mock implementation of ModelAdapter for testing the CAM pipeline
 * without making live API calls or spending tokens.
 */
public final class MockAdapterCAM implements ModelAdapter 
{
    private final String providerName;
    private final String modelName;
    private final Random random;

    public MockAdapterCAM(String providerName, String modelName, long seed) 
    {
        this.providerName = providerName;
        this.modelName    = modelName;
        this.random       = new Random(seed);
    }

    @Override
    public String provider() 
    {
        return providerName;
    }

    @Override
    public String modelId() 
    {
        return modelName;
    }

    @Override
    public ModelResponse complete(ModelRequest request) throws ModelException 
    {
        // Generate deterministic fake scores between 70.0 and 100.0
        double m1Score  = 70.0 + random.nextInt(31);
        double m2Score  = 70.0 + random.nextInt(31);
        String mockJson = getString(m1Score, m2Score);

        long fakeInputTokens  = request.userPrompt().length() / 4;
        long fakeOutputTokens = mockJson.length() / 4;
        long fakeLatencyMs    = 15 + random.nextInt(35);

        return new ModelResponse(
                mockJson,
                modelName,
                modelName + "-mock-v1",
                fakeInputTokens,
                fakeOutputTokens,
                fakeLatencyMs,
                0
        );
    }

    private String getString(double m1Score, double m2Score)
    {
        double totalScore = Math.round(((m1Score + m2Score) / 2.0) * 100.0) / 100.0;

        return String.format("""
            {
              "method_scores": [
                {
                  "method_name": "addFirst",
                  "score": %.2f,
                  "feedback": "Mock feedback: pointer updates look valid."
                },
                {
                  "method_name": "removeFirst",
                  "score": %.2f,
                  "feedback": "Mock feedback: handles empty list edge case."
                }
              ],
              "total_score": %.2f,
              "overall_feedback": "Simulated evaluation from %s."
            }
            """, m1Score, m2Score, totalScore, modelName);
    }
}

