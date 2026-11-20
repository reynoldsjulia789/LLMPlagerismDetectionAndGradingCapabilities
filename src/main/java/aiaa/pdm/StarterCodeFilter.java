package aiaa.pdm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Removes instructor-provided starter code from a submission before similarity scoring.
 *
 * <p>Method: every starter line is reduced to a key made of its code tokens, so whitespace,
 * operator spacing and trailing comments do not matter; a leading {@code }} and trailing
 * {@code {} are dropped so brace placement does not matter either. Comment-only lines are keyed
 * by their whitespace-collapsed comment text. When filtering a submission, each line whose key
 * matches is removed, but only as many times as that key occurs in the starter code (multiset
 * matching): if the starter has one {@code return false;} placeholder, at most one of the
 * student's {@code return false;} lines is removed.
 *
 * <p>Known limitation: starter code whose identifiers were renamed, or whose statements were
 * split or joined across lines, no longer matches and is counted as student-written.
 *
 * <p>Only "substantive" lines (containing a letter or digit) are removed; lines made only of
 * braces/punctuation are kept so the remaining code still reads as code. Blank lines are kept.
 */
public final class StarterCodeFilter {

    private final Map<String, Integer> starterCounts;

    private StarterCodeFilter(Map<String, Integer> starterCounts) {
        this.starterCounts = starterCounts;
    }

    public static StarterCodeFilter fromSources(Collection<String> starterSources) {
        Map<String, Integer> counts = new HashMap<>();
        for (String src : starterSources) {
            for (String line : src.split("\\R")) {
                String norm = normalize(line);
                if (isSubstantive(norm)) {
                    counts.merge(norm, 1, Integer::sum);
                }
            }
        }
        return new StarterCodeFilter(counts);
    }

    public int starterLineCount() {
        return starterCounts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Layout-insensitive key of one line (see class comment). */
    static String normalize(String line) {
        List<String> code = new ArrayList<>();
        String comment = null;
        for (JavaLexer.Token t : JavaLexer.lex(line)) {
            if (t.kind() == JavaLexer.Kind.WHITESPACE) {
                continue;
            }
            if (t.comment()) {
                if (comment == null) {
                    comment = t.text().strip().replaceAll("\\s+", " ");
                }
                continue;
            }
            code.add(t.text());
        }
        while (!code.isEmpty() && code.getFirst().equals("}")) {
            code.removeFirst();
        }
        while (!code.isEmpty() && code.getLast().equals("{")) {
            code.removeLast();
        }
        if (!code.isEmpty()) {
            return String.join(" ", code);
        }
        return comment == null ? "" : comment;
    }

    static boolean isSubstantive(String normalized) {
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.isLetterOrDigit(normalized.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** Result of filtering one submission. */
    public record Result(SourceView view, int removedLines, int substantiveLinesBefore) {
        /** Fraction of substantive lines that matched starter code, 0..1. */
        public double starterFraction() {
            return substantiveLinesBefore == 0 ? 0 : (double) removedLines / substantiveLinesBefore;
        }
    }

    /**
     * Filters all files of one submission. The starter multiset is shared across the
     * submission's files, so starter code is matched regardless of which file it ended up in.
     */
    public Result filter(SourceView original) {
        Map<String, Integer> remaining = new HashMap<>(starterCounts);
        List<SourceView.FileView> files = new ArrayList<>();
        int removed = 0;
        int before = 0;
        for (SourceView.FileView f : original.files()) {
            List<SourceView.Line> kept = new ArrayList<>();
            for (SourceView.Line line : f.lines()) {
                String norm = normalize(line.text());
                if (isSubstantive(norm)) {
                    before++;
                    Integer c = remaining.get(norm);
                    if (c != null && c > 0) {
                        remaining.put(norm, c - 1);
                        removed++;
                        continue;
                    }
                }
                kept.add(line);
            }
            files.add(new SourceView.FileView(f.name(), kept));
        }
        return new Result(new SourceView(original.id(), files), removed, before);
    }
}
