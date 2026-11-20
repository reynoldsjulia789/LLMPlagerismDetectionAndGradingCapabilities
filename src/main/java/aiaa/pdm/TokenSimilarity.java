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
        for (JavaLexer.Token t : JavaLexer.lex(src)) {
            switch (t.kind()) {
                case WHITESPACE, LINE_COMMENT, BLOCK_COMMENT -> { }
                case STRING -> raw.add("STR");
                case CHAR -> raw.add("CHR");
                case NUMBER -> raw.add("NUM");
                case IDENT -> raw.add("ID");
                case KEYWORD, OPERATOR -> raw.add(t.text());
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
