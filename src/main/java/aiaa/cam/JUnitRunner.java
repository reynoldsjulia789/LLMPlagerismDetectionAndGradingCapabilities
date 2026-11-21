package aiaa.cam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Compiles & runs JUnit tests in isolation
 */
public class JUnitRunner
{

    private final Path _hwTestsRoot;
    private final Path _JUnitJarPath;

    public JUnitRunner(Path hwTestsRoot, Path JUnitJarPath)
    {
        this._hwTestsRoot  = hwTestsRoot;
        this._JUnitJarPath = JUnitJarPath;
    }

    /**
     * Runs JUnit tests in an isolated temp directory.
     * Returns pass percentage (0.0 to 100.0), or -1.0 if compilation/execution failed.
     */
    public double runJUnitSuite(String assignment, Path studentDir)
    {
        Path tempDir = null;

        try
        {
            tempDir = Files.createTempDirectory("junitTesting_" + assignment + "_");

            copySubmissionFiles(studentDir, tempDir);
            copyTestFiles(assignment, tempDir);

            if (!compileFiles(tempDir))
            {
                return -1.0;
            }

            return executeJUnit(tempDir);
        }
        catch (Exception ignored)
        {
            return -1.0;
        }
        finally
        {
            deleteDirectory(tempDir);
        }
    }

    private void copySubmissionFiles(Path studentDir, Path tempDir) throws IOException
    {
        try (Stream<Path> files = Files.walk(studentDir))
        {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList())
            {
                String fileName = file.getFileName().toString();

                Files.copy(file, tempDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
//
//                if (!fileName.toLowerCase().contains("tester"))
//                {
//                    Files.copy(file, tempDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
//                }
            }
        }
    }

    private void copyTestFiles(String assignment, Path tempDir) throws IOException
    {
        Path testSourceDir = _hwTestsRoot.resolve(assignment);

        if (!Files.exists(testSourceDir))
        {
            return;
        }
        try (Stream<Path> testFiles = Files.list(testSourceDir))
        {
            for (Path testFile : testFiles.filter(p -> p.toString().endsWith(".java")).toList())
            {
                Files.copy(testFile, tempDir.resolve(testFile.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private boolean compileFiles(Path tempDir) throws IOException, InterruptedException
    {
        List<String> command = new ArrayList<>();
        command.add("javac");
        command.add("-cp");
        command.add(_JUnitJarPath.toAbsolutePath().toString());

        try (Stream<Path> javaFiles = Files.list(tempDir))
        {
            javaFiles.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> command.add(p.getFileName().toString()));
        }

        Process process = new ProcessBuilder(command)
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start();

        String compilerOutput = new String(process.getInputStream().readAllBytes());

        boolean success = process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;

        if (!success)
        {
            System.err.println("=== JAVAC ERROR ===\n" + compilerOutput);
        }

        return success;
    }

    private double executeJUnit(Path tempDir) throws IOException, InterruptedException
    {
        List<String> command = List.of(
                "java", "-jar", _JUnitJarPath.toAbsolutePath().toString(),
                "execute", "--class-path", ".", "--scan-class-path"
        );

        Path outputLog = tempDir.resolve("junit_output.txt");

        Process process = new ProcessBuilder(command)
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(outputLog.toFile())
                .start();

        boolean finishedInTime = process.waitFor(15, TimeUnit.SECONDS);

        if (!finishedInTime)
        {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);

            System.err.println("=== JUNIT TIMED OUT (INFINITE LOOP) ===");
        }

        String output = Files.exists(outputLog) ? Files.readString(outputLog) : "";
        System.out.println("=== JUNIT OUTPUT ===\n" + output);

        return parsePassPercentage(output.toString());
    }

    private double parsePassPercentage(String junitOutput)
    {
        int found  = 0;
        int passed = 0;

        Matcher foundMatcher = Pattern.compile("\\[\\s*(\\d+)\\s+tests found\\s*\\]").matcher(junitOutput);
        if (foundMatcher.find())
        {
            found = Integer.parseInt(foundMatcher.group(1));
        }

        Matcher passedMatcher = Pattern.compile("\\[\\s*(\\d+)\\s+tests successful\\s*\\]").matcher(junitOutput);
        if (passedMatcher.find())
        {
            passed = Integer.parseInt(passedMatcher.group(1));
        }

        return (found == 0) ? 0.0 : ((double) passed / found) * 100.0;
    }

    private void deleteDirectory(Path dir)
    {
        if (dir == null || !Files.exists(dir)) return;

        try (Stream<Path> walk = Files.walk(dir))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(p ->
            {
                try
                {
                    Files.deleteIfExists(p);
                }
                catch (IOException ignored) {}
            });
        }
        catch (IOException ignored) {}
    }
}

