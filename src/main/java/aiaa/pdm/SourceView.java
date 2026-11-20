package aiaa.pdm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The code of one submission (or reference) as it will be compared: possibly with starter
 * lines removed, but every kept line remembers its ORIGINAL line number so evidence
 * locations reported by the LLM point into the real files.
 */
public record SourceView(String id, List<FileView> files) {

    public record Line(int number, String text) {
    }

    public record FileView(String name, List<Line> lines) {
    }

    /** Wraps files unchanged (no starter exclusion). */
    public static SourceView of(String id, Map<String, String> files) {
        List<FileView> views = new ArrayList<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            String[] raw = e.getValue().split("\\R", -1);
            List<Line> lines = new ArrayList<>(raw.length);
            for (int i = 0; i < raw.length; i++) {
                lines.add(new Line(i + 1, raw[i]));
            }
            views.add(new FileView(e.getKey(), lines));
        }
        return new SourceView(id, views);
    }

    /** Plain code (no numbering), used for token similarity. */
    public String plainText() {
        StringBuilder sb = new StringBuilder();
        for (FileView f : files) {
            for (Line l : f.lines()) {
                sb.append(l.text()).append('\n');
            }
        }
        return sb.toString();
    }

    /** Code with file headers and original line numbers, used in LLM prompts. */
    public String numberedText() {
        StringBuilder sb = new StringBuilder();
        for (FileView f : files) {
            if (f.lines().stream().allMatch(l -> l.text().isBlank())) {
                continue;
            }
            sb.append("=== File: ").append(f.name()).append(" ===\n");
            for (Line l : f.lines()) {
                sb.append(String.format("%4d| %s%n", l.number(), l.text()));
            }
        }
        return sb.toString();
    }

    /** Number of non-blank lines. */
    public int codeLineCount() {
        int n = 0;
        for (FileView f : files) {
            for (Line l : f.lines()) {
                if (!l.text().isBlank()) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Non-blank lines that contain a letter or digit (i.e. not just braces / punctuation). */
    public int substantiveLineCount() {
        int n = 0;
        for (FileView f : files) {
            for (Line l : f.lines()) {
                if (StarterCodeFilter.isSubstantive(StarterCodeFilter.normalize(l.text()))) {
                    n++;
                }
            }
        }
        return n;
    }
}
