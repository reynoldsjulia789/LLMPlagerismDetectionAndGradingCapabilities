package aiaa.pdm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Non-LLM baseline: normalized token k-gram similarity.
 *
 * <p>Code is lexed into tokens with comments and whitespace dropped, every identifier
 * replaced by {@code ID}, and literals by {@code STR}/{@code CHR}/{@code NUM}; keywords and
 * operators are kept. {@code import}/{@code package} statements are dropped. This makes the
 * score insensitive to renamed variables, reformatting and comment edits, which are the
 * cheapest disguises. Sequences of k tokens (k-grams) are compared as sets.
 *
 * <ul>
 *   <li>Jaccard = |A∩B| / |A∪B| — the primary baseline score.</li>
 *   <li>Containment = |A∩B| / min(|A|,|B|) — reported alongside; more sensitive to partial copying.</li>
 * </ul>
 * Both are scaled to 0–100.
 */
public final class TokenSimilarity {

    public static final int DEFAULT_K = 5;

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "var", "record", "yield");

    private static final String[] OPERATORS = {
            ">>>=", "<<=", ">>=", ">>>", "...", "->", "::", "==", "!=", "<=", ">=", "&&", "||",
            "++", "--", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>"};

    private TokenSimilarity() {
    }

    public record Score(double jaccard, double containment, int gramsA, int gramsB, int shared) {
    }

    public static Score compare(String codeA, String codeB) {
        return compare(codeA, codeB, DEFAULT_K);
    }

    public static Score compare(String codeA, String codeB, int k) {
        Set<String> a = kGrams(tokenize(codeA), k);
        Set<String> b = kGrams(tokenize(codeB), k);
        if (a.isEmpty() || b.isEmpty()) {
            return new Score(0, 0, a.size(), b.size(), 0);
        }
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        int union = a.size() + b.size() - inter.size();
        double jaccard = 100.0 * inter.size() / union;
        double containment = 100.0 * inter.size() / Math.min(a.size(), b.size());
        return new Score(jaccard, containment, a.size(), b.size(), inter.size());
    }

    static Set<String> kGrams(List<String> tokens, int k) {
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + k <= tokens.size(); i++) {
            grams.add(String.join(" ", tokens.subList(i, i + k)));
        }
        return grams;
    }

    /** Lexes Java source into normalized tokens. */
    static List<String> tokenize(String src) {
        List<String> raw = new ArrayList<>();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (src.startsWith("//", i)) {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                i = end < 0 ? n : end + 3;
                raw.add("STR");
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < n && src.charAt(i) != c && src.charAt(i) != '\n') {
                    i += src.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
                raw.add(c == '"' ? "STR" : "CHR");
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1)))) {
                while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '.' || src.charAt(i) == '_')) {
                    i++;
                }
                raw.add("NUM");
            } else if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < n && Character.isJavaIdentifierPart(src.charAt(i))) {
                    i++;
                }
                String word = src.substring(start, i);
                raw.add(KEYWORDS.contains(word) ? word : "ID");
            } else {
                String op = String.valueOf(c);
                for (String candidate : OPERATORS) {
                    if (src.startsWith(candidate, i)) {
                        op = candidate;
                        break;
                    }
                }
                raw.add(op);
                i += op.length();
            }
        }
        // Drop import/package statements: they are boilerplate, not authored logic.
        List<String> out = new ArrayList<>(raw.size());
        boolean skipping = false;
        for (String t : raw) {
            if (!skipping && (t.equals("import") || t.equals("package"))) {
                skipping = true;
            }
            if (!skipping) {
                out.add(t);
            } else if (t.equals(";")) {
                skipping = false;
            }
        }
        return out;
    }
}
