package aiaa.cam;

import java.util.List;

public class GradingMath
{
    public record ErrorMetrics(
            int sampleSize,
            double meanAbsoluteError,
            double rootMeanSquaredError,
            double meanSignedError,
            double fractionWithin5Pct,
            double fractionWithin10Pct) {}

    public static ErrorMetrics computeErrorMetrics(List<Double> predicted, List<Double> actual)
    {
        int n = Math.min(predicted.size(), actual.size());
        if (n == 0)
        {
            return new ErrorMetrics(0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        double sumSignedError  = 0.0;
        double sumAbsError     = 0.0;
        double sumSquaredError = 0.0;
        int    within5Count    = 0;
        int    within10Count   = 0;

        for (int i = 0; i < n; i++)
        {
            double signedError = predicted.get(i) - actual.get(i);
            double absError    = Math.abs(signedError);

            sumSignedError  += signedError;
            sumAbsError     += absError;
            sumSquaredError += (signedError * signedError);

            if (absError <= 5.0) within5Count++;
            if (absError <= 10.0) within10Count++;
        }

        return new ErrorMetrics(
                n,
                sumAbsError / n,
                Math.sqrt(sumSquaredError / n),
                sumSignedError / n,
                (double) within5Count / n,
                (double) within10Count / n
        );
    }

    public static double mean(List<Double> values)
    {
        if (values.isEmpty()) return 0.0;

        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    public static double stdDev(List<Double> values)
    {
        if (values.size() < 2) return 0.0;
        double avg      = mean(values);
        double variance = values.stream()
                .mapToDouble(v -> Math.pow(v - avg, 2))
                .average()
                .orElse(0.0);

        return Math.sqrt(variance);
    }
}

