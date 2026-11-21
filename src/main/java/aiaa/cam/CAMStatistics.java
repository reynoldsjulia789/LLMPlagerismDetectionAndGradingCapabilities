package aiaa.cam;

import aiaa.cam.AssessmentDataLoader.GradeRecord;
import aiaa.cam.AssessmentDataLoader.TelemetryRecord;
import aiaa.cam.GradingMath.ErrorMetrics;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CAMStatistics
{
    private static final Path OUTPUT_DIR = Paths.get("assessment_results");

    public static void generateAllReports() throws IOException
    {
        List<GradeRecord>     gradeRecords     = AssessmentDataLoader.loadGrades(OUTPUT_DIR.resolve("grade_comparison.csv"));
        List<TelemetryRecord> telemetryRecords = AssessmentDataLoader.loadTelemetry(OUTPUT_DIR.resolve("experiment_log.csv"));

        writeGradingMetricsCsv(gradeRecords, OUTPUT_DIR.resolve("grading_metrics_summary.csv"));
        writeEfficiencyCsv(telemetryRecords, OUTPUT_DIR.resolve("efficiency_summary.csv"));
        writeScatterPlotCsv(gradeRecords, OUTPUT_DIR.resolve("scatter_plot_chart_data.csv"));

        System.out.println("CAM statistics and chart CSVs generated in: " + OUTPUT_DIR.toAbsolutePath());
    }

    private static void writeGradingMetricsCsv(List<GradeRecord> records, Path outFile) throws IOException
    {
        String[] scopes = {"Hw1", "Hw2", "Hw8", "POOLED"};
        String[] methods = {
                "GPT_Only", "Gemma_Only", "Claude_Only"
        };

        try (PrintWriter writer = new PrintWriter(new FileWriter(outFile.toFile())))
        {
            writer.println("scope,model,n,mae,rmse,mean_signed_error,within_5_pct,within_10_pct");

            for (String scope : scopes)
            {
                List<GradeRecord> subset = records.stream()
                        .filter(r -> "POOLED".equals(scope) || r.assignment().equalsIgnoreCase(scope))
                        .toList();

                if (subset.isEmpty()) continue;

                List<Double> baselineGrades = subset.stream()
                        .map(GradeRecord::junitBaselineGrade)
                        .toList();

                for (String method : methods)
                {
                    List<Double> predictedGrades = subset.stream()
                            .map(r -> extractScore(r, method))
                            .toList();

                    ErrorMetrics metrics = GradingMath.computeErrorMetrics(predictedGrades, baselineGrades);

                    writer.printf("%s,%s,%d,%.2f,%.2f,%.2f,%.2f%%,%.2f%%%n",
                            scope,
                            method,
                            metrics.sampleSize(),
                            metrics.meanAbsoluteError(),
                            metrics.rootMeanSquaredError(),
                            metrics.meanSignedError(),
                            metrics.fractionWithin5Pct() * 100.0,
                            metrics.fractionWithin10Pct() * 100.0);
                }
            }
        }
    }

    private static double extractScore(GradeRecord r, String model)
    {
        return switch (model)
        {
            case "GPT"    -> r.camGpt();
            case "Gemma"  -> r.camGemma();
            case "Claude" -> r.camClaude();
            default       -> 0.0;
        };
    }

    private static void writeEfficiencyCsv(List<TelemetryRecord> logs, Path outFile) throws IOException
    {
        Map<String, List<TelemetryRecord>> byModel = logs.stream()
                .collect(Collectors.groupingBy(
                        t -> t.provider() + ":" + t.requestedModel(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        try (PrintWriter writer = new PrintWriter(new FileWriter(outFile.toFile())))
        {
            writer.println("model_id,submission_count,total_api_calls,total_retries,"
                    + "total_input_tokens,total_output_tokens,mean_latency_ms,std_dev_latency_ms,"
                    + "total_cost_usd,mean_cost_per_submission_usd");

            for (var entry : byModel.entrySet())
            {
                String modelKey = entry.getKey();
                List<TelemetryRecord> group = entry.getValue();

                int count           = group.size();
                int totalCalls      = group.stream().mapToInt(TelemetryRecord::apiCalls).sum();
                int totalRetries    = group.stream().mapToInt(TelemetryRecord::retries).sum();
                long totalInTokens  = group.stream().mapToLong(TelemetryRecord::inputTokens).sum();
                long totalOutTokens = group.stream().mapToLong(TelemetryRecord::outputTokens).sum();
                double totalCost    = group.stream().mapToDouble(TelemetryRecord::costUsd).sum();

                List<Double> latencies = group.stream().map(TelemetryRecord::latencyMs).toList();
                double meanLatency     = GradingMath.mean(latencies);
                double stdDevLatency   = GradingMath.stdDev(latencies);

                writer.printf("%s,%d,%d,%d,%d,%d,%.2f,%.2f,%.4f,%.6f%n",
                        modelKey, count, totalCalls, totalRetries,
                        totalInTokens, totalOutTokens, meanLatency, stdDevLatency,
                        totalCost, (count == 0 ? 0.0 : totalCost / count));
            }
        }
    }

    private static void writeScatterPlotCsv(List<GradeRecord> records, Path outFile) throws IOException
    {
        try (PrintWriter writer = new PrintWriter(new FileWriter(outFile.toFile())))
        {
            writer.println("submission_id,assignment,ocaa_baseline_grade,equality_line,"
                    + "cam_gpt,cam_gemma,cam_claude,hybrid_gpt,hybrid_gemma,hybrid_claude");

            for (GradeRecord r : records)
            {
                writer.printf("%s,%s,%.2f,%.2f,%.2f,%.2f,%.2fn",
                        r.submissionId(), r.assignment(), r.junitBaselineGrade(), r.junitBaselineGrade(),
                        r.camGpt(), r.camGemma(), r.camClaude());
            }
        }
    }
}

