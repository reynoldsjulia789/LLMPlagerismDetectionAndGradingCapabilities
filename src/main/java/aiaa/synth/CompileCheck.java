package aiaa.synth;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Compiles a set of in-memory Java files with the JDK compiler (no code is executed). */
public final class CompileCheck {

    public record Result(boolean ok, String diagnostics) {
    }

    private CompileCheck() {
    }

    public static boolean compiles(Map<String, String> files) {
        return compile(files).ok();
    }

    public static Result compile(Map<String, String> files) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No Java compiler available; run with a JDK, not a JRE");
        }
        List<JavaFileObject> units = new ArrayList<>();
        files.forEach((name, content) -> units.add(new Source(name, content)));
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path out = null;
        try {
            out = Files.createTempDirectory("aiaa-compile");
            try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
                List<String> options = List.of("-d", out.toString(), "-proc:none", "-nowarn", "-Xlint:none",
                        "-implicit:none");
                boolean ok = compiler.getTask(null, fm, diagnostics, options, null, units).call();
                StringBuilder sb = new StringBuilder();
                int shown = 0;
                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    if (d.getKind() != Diagnostic.Kind.ERROR || shown++ >= 10) {
                        continue;
                    }
                    String file = d.getSource() == null ? "?" : d.getSource().getName().replaceFirst("^/", "");
                    sb.append(file).append(':').append(d.getLineNumber()).append(": ")
                            .append(d.getMessage(Locale.ROOT)).append('\n');
                }
                return new Result(ok, sb.toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteQuietly(out);
        }
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

    private static final class Source extends SimpleJavaFileObject {
        private final String content;

        Source(String name, String content) {
            super(URI.create("string:///" + name.replace('\\', '/')), Kind.SOURCE);
            this.content = content;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }
}
