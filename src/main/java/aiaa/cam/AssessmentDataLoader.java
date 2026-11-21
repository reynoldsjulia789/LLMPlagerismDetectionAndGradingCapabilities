package aiaa.cam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * reads CSVs
 */
public class AssessmentDataLoader
{

    public record GradeRecord(
            String submissionId,
            String assignment,
            String cohort,
            double junitBaselineGrade,
            double camGpt,
            double camGemma,
            double camClaude) {}

    public record TelemetryRecord(
            String submissionId,
            String assignment,
            String provider,
            String requestedModel,
            long inputTokens,
            long outputTokens,
            int apiCalls,
            int retries,
            double latencyMs,
            double costUsd) {}

    public static List<GradeRecord> loadGrades(Path gradeCsv) throws IOException
    {
        List<GradeRecord> records = new ArrayList<>();
        if (!Files.exists(gradeCsv)) return records;

        List<String> lines = Files.readAllLines(gradeCsv);

        for (int i = 1; i < lines.size(); i++)
        {
            String[] cols = lines.get(i).split(",");
            if (cols.length < 8) continue;

            records.add(new GradeRecord(
                    cols[0],                        // submission_id
                    cols[1],                        // assignment
                    cols[2],                        // cohort
                    Double.parseDouble(cols[3]),    // ocaa_junit_grade
                    Double.parseDouble(cols[5]),    // cam_gpt_grade
                    Double.parseDouble(cols[6]),    // cam_gemma_grade
                    Double.parseDouble(cols[7])    // cam_claude_grade
            ));
        }
        return records;
    }

    public static List<TelemetryRecord> loadTelemetry(Path logCsv) throws IOException
    {
        List<TelemetryRecord> records = new ArrayList<>();
        if (!Files.exists(logCsv)) return records;

        List<String> lines = Files.readAllLines(logCsv);

        for (int i = 1; i < lines.size(); i++)
        {
            // Regex split preserves commas inside quoted parameter strings
            String[] cols = lines.get(i).split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
            if (cols.length < 14) continue;

            records.add(new TelemetryRecord(
                    cols[0],                        // submission_id
                    cols[1],                        // assignment
                    cols[2],                        // provider
                    cols[3],                        // requested_model
                    Long.parseLong(cols[8]),        // input_tokens
                    Long.parseLong(cols[9]),        // output_tokens
                    Integer.parseInt(cols[10]),     // api_calls
                    Integer.parseInt(cols[11]),     // retries
                    Double.parseDouble(cols[12]),   // latency_ms
                    Double.parseDouble(cols[13])    // cost_usd
            ));
        }

        return records;
    }
}

