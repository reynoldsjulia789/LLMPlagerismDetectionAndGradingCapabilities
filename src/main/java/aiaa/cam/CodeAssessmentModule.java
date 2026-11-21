package aiaa.cam;

import aiaa.llm.ModelAdapter;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Loops through submissions & coordinates everything...
 * Main file of CodeAssessmentModule
 */
public class CodeAssessmentModule
{

    private static final Path SUBMISSIONS_ROOT = Paths.get("src/main/resources/HomeworkSubmissions");
    private static final Path HW_TESTS_ROOT    = Paths.get("src/main/resources/hwTests");
    private static final Path PROMPTS_ROOT     = Paths.get("src/main/resources/prompts");
    private static final Path JUNIT_JAR_PATH   = Paths.get("lib/junit-platform-console-standalone.jar");
    private static final Path OUTPUT_DIR       = Paths.get("assessment_results");

    private static final String PROMPT_VERSION = "cam_v1.0";
    private static final String RUBRIC_VERSION = "rubric_v1.0";
    private static final int MAX_OUTPUT_TOKENS = 2048;
    private static final Double TEMPERATURE    = 0.0;

    private final List<ModelAdapter> modelAdapters;
    private final JUnitRunner        junitRunner;
    private final CAMPromptLoader    promptLoader;
    private final LLMGrader          llmGrader;

    private int maxSubmissions = -1;

    public CodeAssessmentModule(
            List<ModelAdapter> modelAdapters,
            Map<String, Double> inputPricePer1k,
            Map<String, Double> outputPricePer1k) throws IOException
    {
        this.modelAdapters = modelAdapters;
        this.junitRunner   = new JUnitRunner(HW_TESTS_ROOT, JUNIT_JAR_PATH);
        this.promptLoader  = new CAMPromptLoader(PROMPTS_ROOT);
        this.llmGrader     = new LLMGrader(
                promptLoader, inputPricePer1k, outputPricePer1k, MAX_OUTPUT_TOKENS, TEMPERATURE);
    }

    public void setMaxSubmissions(int maxSubmissions)
    {
        if (maxSubmissions >= -1)
        {
            this.maxSubmissions = maxSubmissions;
        }
    }

    public void runFullAssessment() throws IOException
    {
        Files.createDirectories(OUTPUT_DIR);
        String runId = "RUN_" + UUID.randomUUID().toString().substring(0, 8);

        List<String> providers = modelAdapters.stream()
                .map(adapter -> adapter.provider().toLowerCase())
                .toList();

        try (PrintWriter gradeWriter  = new PrintWriter(new FileWriter(OUTPUT_DIR.resolve("grade_comparison.csv").toFile()));
             PrintWriter methodWriter = new PrintWriter(new FileWriter(OUTPUT_DIR.resolve("method_grades.csv").toFile()));
             PrintWriter logWriter    = new PrintWriter(new FileWriter(OUTPUT_DIR.resolve("experiment_log.csv").toFile())))
        {

            StringBuilder header = new StringBuilder("submission_id,assignment,cohort,junit_baseline_grade,grade_scope");

            for (String provider : providers)
            {
                header.append(",").append(provider).append("_grade");
            }

            for (String provider : providers)
            {
                header.append(",error_").append(provider);
            }

            header.append(",run_id,status");
            gradeWriter.println(header);

            methodWriter.println("submission_id,assignment,provider,model_id,method_name,method_score,feedback,run_id");

            logWriter.println("submission_id,assignment,provider,requested_model,returned_model,"
                    + "prompt_version,parameters,rubric_version,input_tokens,output_tokens,"
                    + "api_calls,retries,latency_ms,cost_usd,seed,timestamp,run_id");

            int processedCount = 0;

            for (String assignment : List.of("Hw1", "Hw2", "Hw8"))
            {
                if (maxSubmissions > 0 && processedCount >= maxSubmissions) break;

                Path assignmentDir = SUBMISSIONS_ROOT.resolve(assignment);

                if (!Files.exists(assignmentDir)) continue;

                try (Stream<Path> quarterDirs = Files.list(assignmentDir))
                {
                    for (Path quarterDir : quarterDirs.filter(Files::isDirectory).sorted().toList())
                    {
                        if (maxSubmissions > 0 && processedCount >= maxSubmissions) break;

                        String cohort = quarterDir.getFileName().toString();

                        try (Stream<Path> studentDirs = Files.list(quarterDir))
                        {
                            for (Path studentDir : studentDirs.filter(Files::isDirectory).sorted().toList())
                            {
                                if (maxSubmissions > 0 && processedCount >= maxSubmissions)
                                {
                                    break;
                                }

                                System.out.println("Assessing " + studentDir);
                                assessStudent(assignment, cohort, studentDir, runId, gradeWriter, methodWriter, logWriter);

                                processedCount++;
                            }
                        }
                    }
                }
            }
        }

        System.out.println("Assessment complete. Results written to: " + OUTPUT_DIR.toAbsolutePath());
    }

    private void assessStudent(
            String assignment,
            String cohort,
            Path studentDir,
            String runId,
            PrintWriter gradeWriter,
            PrintWriter methodWriter,
            PrintWriter logWriter) throws IOException
    {

        String submissionId = studentDir.getFileName().toString();
        String status = "SUCCESS";

        // Run JUnit tests
        System.out.println("JUnit");
        double junitGrade = junitRunner.runJUnitSuite(assignment, studentDir);

        if (junitGrade < 0)
        {
            status = "COMPILE_OR_TEST_ERROR";
            junitGrade = 0.0;
        }

        // Read student source files
        String studentCode = promptLoader.readStudentSourceCode(studentDir);

        // Evaluate with each LLM provider
        System.out.println("LLM evaluation");
        List<Double> orderedScores = new ArrayList<>();

        for (ModelAdapter adapter : modelAdapters)
        {
            String provider = adapter.provider().toLowerCase();

            System.out.println("Model - " + provider + ":" + adapter.modelId());

            LLMGrader.GradingOutcome outcome = llmGrader.gradeSubmission(adapter, assignment, studentCode);

            orderedScores.add(outcome.totalScore());

            if (!outcome.success())
            {
                status = "LLM_ERROR_" + provider.toUpperCase();

                System.out.println(status);
            }

            System.out.println("succeeded");

            for (LLMGrader.MethodScore ms : outcome.methodScores())
            {
                methodWriter.printf("%s,%s,%s,%s,\"%s\",%.2f,\"%s\",%s%n",
                        submissionId, assignment, outcome.provider(), outcome.returnedModel(),
                        sanitizeCsv(ms.methodName()), ms.score(), sanitizeCsv(ms.feedback()), runId);
            }

            String tempLog    = "anthropic".equalsIgnoreCase(adapter.provider()) ? "null(unsupported)" : String.valueOf(TEMPERATURE);
            String paramsText = "temperature=" + tempLog + ";max_output_tokens=" + MAX_OUTPUT_TOKENS;

            logWriter.printf("%s,%s,%s,%s,%s,%s,\"%s\",%s,%d,%d,%d,%d,%d,%.6f,N/A,%s,%s%n",
                    submissionId, assignment, outcome.provider(), outcome.requestedModel(), outcome.returnedModel(),
                    PROMPT_VERSION, paramsText, RUBRIC_VERSION,
                    outcome.inputTokens(), outcome.outputTokens(),
                    1 + outcome.retries(), outcome.retries(), outcome.latencyMs(), outcome.costUsd(),
                    Instant.now().toString(), runId);
        }

        StringBuilder row = new StringBuilder();

        row.append(String.format("%s,%s,%s,%.2f,CODE_NORMALIZED_100",
                submissionId, assignment, cohort, junitGrade));

        for (double score : orderedScores)
        {
            row.append(String.format(",%.2f", score));
        }

        for (double score : orderedScores)
        {
            row.append(String.format(",%.2f", score - junitGrade));
        }

        row.append(String.format(",%s,%s", runId, status));

        gradeWriter.println(row);
    }

    private String sanitizeCsv(String text)
    {
        return text.replace("\"", "'").replace("\r", " ").replace("\n", " ");
    }
}

