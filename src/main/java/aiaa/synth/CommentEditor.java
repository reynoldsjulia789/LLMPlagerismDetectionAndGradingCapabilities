package aiaa.synth;

import aiaa.pdm.JavaLexer;
import aiaa.pdm.JavaLexer.Kind;
import aiaa.pdm.JavaLexer.Token;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Edits comments only: deletes some, rewords others, and adds new comments above some
 * methods. Code tokens are never touched.
 */
final class CommentEditor {

    record Result(Map<String, String> files, int deleted, int reworded, int added) {
        boolean changed() {
            return deleted + reworded + added > 0;
        }
    }

    private static final Map<String, String> WORDS = Map.ofEntries(
            Map.entry("returns", "gives back"), Map.entry("return", "give back"),
            Map.entry("removes", "deletes"), Map.entry("remove", "delete"),
            Map.entry("adds", "inserts"), Map.entry("add", "insert"),
            Map.entry("checks", "verifies"), Map.entry("check", "verify"),
            Map.entry("element", "item"), Map.entry("elements", "items"),
            Map.entry("node", "entry"), Map.entry("nodes", "entries"),
            Map.entry("method", "function"), Map.entry("loop", "iterate"),
            Map.entry("index", "position"), Map.entry("pointer", "reference"),
            Map.entry("data", "value"), Map.entry("first", "front"),
            Map.entry("traverse", "walk"), Map.entry("traverses", "walks"),
            Map.entry("create", "make"), Map.entry("creates", "makes"),
            Map.entry("empty", "blank"), Map.entry("size", "length"));
    private static final Pattern WORD = Pattern.compile("\\b(" + String.join("|", WORDS.keySet()) + ")\\b",
            Pattern.CASE_INSENSITIVE);

    private static final List<String> TEMPLATES = List.of("%s implementation", "helper for %s",
            "handles the %s operation", "%s logic below", "TODO: double check edge cases",
            "iterative approach", "walks the list", "%s - done", "see assignment spec");

    private CommentEditor() {
    }

    static Result edit(Map<String, String> files, Random rnd) {
        long seed = rnd.nextLong();
        Result r = edit(files, new Random(seed), false);
        // Code without comments, where no random insertion happened: force one added comment.
        return r.changed() ? r : edit(files, new Random(seed), true);
    }

    private static Result edit(Map<String, String> files, Random rnd, boolean forceFirst) {
        Map<String, String> out = new LinkedHashMap<>();
        int deleted = 0;
        int reworded = 0;
        int added = 0;
        for (Map.Entry<String, String> e : files.entrySet()) {
            List<Token> tokens = JavaLexer.lex(e.getValue());
            Map<Integer, String> replace = new HashMap<>();
            Map<Integer, String> prefix = new HashMap<>();
            for (int i = 0; i < tokens.size(); i++) {
                Token t = tokens.get(i);
                if (!t.comment()) {
                    continue;
                }
                double r = rnd.nextDouble();
                if (r < 0.35) {
                    replace.put(i, deletion(tokens, i));
                    deleted++;
                } else if (r < 0.85) {
                    String w = reword(t);
                    if (w == null) {
                        replace.put(i, deletion(tokens, i));
                        deleted++;
                    } else if (!w.equals(t.text())) {
                        replace.put(i, w);
                        reworded++;
                    }
                }
            }
            for (JavaStructure.TypeBody type : JavaStructure.topLevelTypes(tokens)) {
                for (JavaStructure.Member m : type.members()) {
                    boolean force = forceFirst && added == 0;
                    if (m.kind() == JavaStructure.MemberKind.METHOD && (rnd.nextDouble() < 0.4 || force)) {
                        String template = TEMPLATES.get(rnd.nextInt(TEMPLATES.size()));
                        String indent = JavaStructure.indentBefore(tokens, m.firstCode());
                        prefix.put(m.firstCode(), "// " + template.replace("%s", m.name()) + "\n" + indent);
                        added++;
                    }
                }
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tokens.size(); i++) {
                sb.append(prefix.getOrDefault(i, ""));
                sb.append(replace.getOrDefault(i, tokens.get(i).text()));
            }
            out.put(e.getKey(), sb.toString());
        }
        return new Result(out, deleted, reworded, added);
    }

    /** Empty, or a single space when removing the comment would glue two code tokens together. */
    private static String deletion(List<Token> tokens, int i) {
        boolean wsBefore = i == 0 || tokens.get(i - 1).kind() == Kind.WHITESPACE;
        boolean wsAfter = i + 1 >= tokens.size() || tokens.get(i + 1).kind() == Kind.WHITESPACE;
        return wsBefore || wsAfter ? "" : " ";
    }

    /** Reworded comment text, or null if it should be deleted instead. */
    private static String reword(Token t) {
        Matcher m = WORD.matcher(t.text());
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        while (m.find()) {
            any = true;
            String rep = WORDS.get(m.group(1).toLowerCase());
            if (Character.isUpperCase(m.group(1).charAt(0))) {
                rep = Character.toUpperCase(rep.charAt(0)) + rep.substring(1);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        if (any) {
            return sb.toString();
        }
        if (t.kind() == Kind.LINE_COMMENT && !t.text().contains("*/")) {
            return "/*" + t.text().substring(2) + " */"; // restyle // comment as /* */
        }
        return t.kind() == Kind.LINE_COMMENT ? null : t.text();
    }
}
