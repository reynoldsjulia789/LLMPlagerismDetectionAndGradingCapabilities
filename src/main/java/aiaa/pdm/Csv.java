package aiaa.pdm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Minimal RFC 4180 CSV reader/writer (UTF-8, CRLF line endings for Excel compatibility). */
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

    /** Reads a CSV with a header row into maps (missing trailing fields become ""). Missing file = empty list. */
    public static List<Map<String, String>> read(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<Map<String, String>> out = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<List<String>> records = parse(r);
            if (records.isEmpty()) {
                return out;
            }
            List<String> header = records.getFirst();
            for (List<String> rec : records.subList(1, records.size())) {
                Map<String, String> row = new HashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    row.put(header.get(i), i < rec.size() ? rec.get(i) : "");
                }
                out.add(row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
        return out;
    }

    private static List<List<String>> parse(BufferedReader r) throws IOException {
        List<List<String>> records = new ArrayList<>();
        List<String> rec = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        int c;
        while ((c = r.read()) != -1) {
            char ch = (char) c;
            any = true;
            if (quoted) {
                if (ch == '"') {
                    r.mark(1);
                    int nxt = r.read();
                    if (nxt == '"') {
                        field.append('"');
                    } else {
                        quoted = false;
                        if (nxt != -1) {
                            r.reset();
                        }
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                rec.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n') {
                rec.add(field.toString());
                field.setLength(0);
                records.add(rec);
                rec = new ArrayList<>();
                any = false;
            } else if (ch != '\r') {
                field.append(ch);
            }
        }
        if (any) {
            rec.add(field.toString());
            records.add(rec);
        }
        return records;
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
            return Double.isNaN(d) ? "" : String.format(Locale.ROOT, "%.2f", d);
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
