package aiaa.pdm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Minimal RFC 4180 CSV writer (UTF-8, CRLF line endings for Excel compatibility). */
public final class Csv {

    private Csv() {
    }

    public static void write(Path file, List<String> header, List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder();
        appendRow(sb, header.stream().map(h -> (Object) h).toList());
        for (List<Object> row : rows) {
            appendRow(sb, row);
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    private static void appendRow(StringBuilder sb, List<Object> row) {
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(format(row.get(i))));
        }
        sb.append("\r\n");
    }

    static String format(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Double d) {
            return String.format(Locale.ROOT, "%.2f", d);
        }
        return v.toString();
    }

    static String escape(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
