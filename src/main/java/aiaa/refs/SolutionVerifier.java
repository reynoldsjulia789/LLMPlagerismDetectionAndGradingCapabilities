package aiaa.refs;

import aiaa.data.AssignmentMaterials;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Checks a candidate solution against the assignment: compiles it together with the supplied testers
 * (always the instructor's unchanged files), runs the main class in a separate JVM with a timeout, and
 * compares the output with the description's "CORRECT OUTPUT" when there is one. HW8 has no fixed output:
 * its Tester must print two {@code true} lines and matching sizes, and an independent harness checks both
 * sorts against {@code Arrays.sort}.
 *
 * <p>This executes the candidate code on this machine (separate process, temporary directory, timeout).
 */
public final class SolutionVerifier {

    public record Result(boolean compiled, boolean outputOk, Boolean harnessOk, String detail, String stdout) {
        public boolean verified() {
            return compiled && outputOk && (harnessOk == null || harnessOk);
        }
    }

    private static final Pattern SIZE_LINE = Pattern.compile("(?i)size of list (A2?) is:?\\s*(\\d+)");

    private SolutionVerifier() {
    }

    public static Result verify(AssignmentMaterials m, Map<String, String> solution, Duration timeout) {
        Map<String, String> files = new LinkedHashMap<>(solution);
        files.putAll(m.testerFiles()); // supplied testers always win: they must be unchanged
        boolean hw8Style = m.expectedOutput().isEmpty();
        if (hw8Style) {
            files.put("SortCheck.java", resource("verify/SortCheck.java"));
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("aiaa-verify");
            Path classes = Files.createDirectories(dir.resolve("classes"));
            String errors = compile(files, dir.resolve("src"), classes);
            if (errors != null) {
                return new Result(false, false, null, "does not compile:\n" + errors, "");
            }
            String main = m.homework().mainClass();
            Run run = run(classes, main, dir, timeout);
            if (run.timedOut()) {
                return new Result(true, false, null, main + " did not finish within " + timeout.toSeconds() + "s", run.out());
            }
            List<String> actual = normalize(run.out());
            if (!hw8Style) {
                String diff = firstDifference(m.expectedOutput(), actual);
                return new Result(true, diff == null, null,
                        diff == null ? "output identical to CORRECT OUTPUT (" + actual.size() + " lines)" : diff, run.out());
            }
            String problem = checkSortOutput(actual);
            Run harness = run(classes, "SortCheck", dir, timeout);
            boolean harnessOk = !harness.timedOut() && harness.exit() == 0 && harness.out().contains("independent check OK");
            String detail = (problem == null ? "Tester output OK" : problem) + "; independent sort check: "
                    + (harnessOk ? "OK" : "FAILED " + abbreviate(harness.err().isBlank() ? harness.out() : harness.err()));
            return new Result(true, problem == null, harnessOk, detail, run.out());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteQuietly(dir);
        }
    }

    private static String checkSortOutput(List<String> lines) {
        long trues = lines.stream().filter(l -> l.equalsIgnoreCase("true")).count();
        if (lines.stream().anyMatch(l -> l.equalsIgnoreCase("false"))) {
            return "Tester printed false (isSorted failed)";
        }
        if (trues < 2) {
            return "Tester should print isSorted() = true for both lists, found " + trues;
        }
        Map<String, Long> sizes = new LinkedHashMap<>();
        for (String l : lines) {
            Matcher mm = SIZE_LINE.matcher(l);
            if (mm.find()) {
                sizes.put(mm.group(1).toUpperCase(Locale.ROOT), Long.parseLong(mm.group(2)));
            }
        }
        if (sizes.size() == 2 && !sizes.get("A").equals(sizes.get("A2"))) {
            return "sizes of A and A2 differ: " + sizes;
        }
        return null;
    }

    static List<String> normalize(String out) {
        return out.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
    }

    private static String firstDifference(List<String> expected, List<String> actual) {
        for (int i = 0; i < Math.max(expected.size(), actual.size()); i++) {
            String e = i < expected.size() ? expected.get(i) : "(end of output)";
            String a = i < actual.size() ? actual.get(i) : "(end of output)";
            if (!e.equals(a)) {
                return "output differs at line " + (i + 1) + ": expected \"" + e + "\" but got \"" + a + "\"";
            }
        }
        return null;
    }

    private static String compile(Map<String, String> files, Path src, Path classes) throws IOException {
        Files.createDirectories(src);
        List<Path> paths = new ArrayList<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path p = src.resolve(e.getKey());
            Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
            paths.add(p);
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No Java compiler available; run with a JDK");
        }
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diags, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean ok = compiler.getTask(null, fm, diags, List.of("-d", classes.toString(), "-proc:none", "-nowarn",
                    "-Xlint:none", "-encoding", "UTF-8"), null, fm.getJavaFileObjectsFromPaths(paths)).call();
            if (ok) {
                return null;
            }
        }
        StringBuilder sb = new StringBuilder();
        List<Diagnostic<? extends JavaFileObject>> all = diags.getDiagnostics();
        List<Diagnostic<? extends JavaFileObject>> errors = all.stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR).toList();
        for (Diagnostic<? extends JavaFileObject> d : (errors.isEmpty() ? all : errors).stream().limit(10).toList()) {
            String file = d.getSource() == null ? "?" : d.getSource().getName().replaceAll(".*[/\\\\]", "");
            sb.append(file).append(':').append(d.getLineNumber()).append(": ").append(d.getMessage(Locale.ROOT)).append('\n');
        }
        return sb.isEmpty() ? "(compiler reported failure without diagnostics)" : sb.toString();
    }

    private record Run(int exit, String out, String err, boolean timedOut) {
    }

    private static Run run(Path classes, String mainClass, Path workDir, Duration timeout) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path out = workDir.resolve(mainClass + ".out");
        Path err = workDir.resolve(mainClass + ".err");
        Process p = new ProcessBuilder(java, "-Xmx512m", "-cp", classes.toString(), mainClass)
                .directory(workDir.toFile())
                .redirectOutput(out.toFile())
                .redirectError(err.toFile())
                .start();
        p.getOutputStream().close();
        boolean finished;
        try {
            finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            p.destroyForcibly();
        }
        return new Run(finished ? p.exitValue() : -1, Files.readString(out, StandardCharsets.UTF_8),
                Files.readString(err, StandardCharsets.UTF_8), !finished);
    }

    static String resource(String name) {
        try (InputStream in = SolutionVerifier.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String abbreviate(String s) {
        String t = s.strip();
        return t.length() > 200 ? t.substring(0, 200) + "..." : t;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // temp dir; best effort
        }
    }
}
