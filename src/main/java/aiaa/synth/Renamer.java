package aiaa.synth;

import aiaa.pdm.JavaLexer;
import aiaa.pdm.JavaLexer.Kind;
import aiaa.pdm.JavaLexer.Token;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Consistently renames declared variables (locals, parameters and optionally fields)
 * across all files of a submission. Method names, type names and members of library
 * objects (e.g. {@code arr.length}) are never renamed.
 */
final class Renamer {

    enum Scope { FIELDS_AND_LOCALS, LOCALS_ONLY }

    record Result(Map<String, String> files, Map<String, String> renames) {
    }

    private static final Map<String, List<String>> SYNONYMS = Map.ofEntries(
            Map.entry("head", List.of("first", "front", "sentinel", "start", "dummy")),
            Map.entry("tail", List.of("last", "rear", "back", "end")),
            Map.entry("size", List.of("count", "numItems", "length", "total", "n")),
            Map.entry("count", List.of("total", "tally", "num", "counter")),
            Map.entry("current", List.of("cur", "walker", "ptr", "runner", "iter")),
            Map.entry("curr", List.of("cur", "walker", "ptr", "runner")),
            Map.entry("cur", List.of("curr", "walker", "ptr", "runner")),
            Map.entry("temp", List.of("tmp", "hold", "holder", "scratch")),
            Map.entry("tmp", List.of("temp", "hold", "holder")),
            Map.entry("node", List.of("nd", "item", "entry", "link")),
            Map.entry("newNode", List.of("fresh", "created", "toAdd", "added", "nn")),
            Map.entry("prev", List.of("before", "previous", "trail", "behind")),
            Map.entry("previous", List.of("prev", "before", "trail")),
            Map.entry("next", List.of("after", "link", "forward", "nxt", "succ")),
            Map.entry("data", List.of("value", "val", "item", "element", "payload")),
            Map.entry("index", List.of("idx", "pos", "position", "where")),
            Map.entry("i", List.of("k", "idx", "x", "m")),
            Map.entry("j", List.of("q", "jdx", "y", "w")),
            Map.entry("result", List.of("res", "answer", "out", "ret")),
            Map.entry("list", List.of("lst", "other", "items", "src")),
            Map.entry("e", List.of("elem", "item", "x", "obj")),
            Map.entry("o", List.of("obj", "target", "item", "key")),
            Map.entry("value", List.of("val", "v", "item", "content")),
            Map.entry("found", List.of("located", "hit", "exists", "present")),
            Map.entry("queue", List.of("q", "line", "pending", "work")),
            Map.entry("first", List.of("front", "start", "head1", "lead")),
            Map.entry("last", List.of("end", "back", "rear", "final1")));

    private static final List<String> POOL = List.of("alpha", "beta", "gamma", "probe", "cursor", "marker",
            "holder", "slot", "cell", "pivot", "scan", "ref", "elemRef", "target", "victim", "walker", "seeker",
            "tracker", "anchor", "bucket", "piece", "chunk", "token", "unit", "bit", "spot");

    private Renamer() {
    }

    static Result rename(Map<String, String> files, Scope scope, Random rnd) {
        Map<String, List<Token>> lexed = new LinkedHashMap<>();
        files.forEach((k, v) -> lexed.put(k, JavaLexer.lex(v)));

        Set<String> allIdents = new HashSet<>();
        Set<String> typeNames = new HashSet<>();
        Set<String> fields = new TreeSet<>();
        Set<String> locals = new TreeSet<>();
        Set<String> dotted = new HashSet<>();
        for (List<Token> tokens : lexed.values()) {
            collect(JavaLexer.significant(tokens), allIdents, typeNames, fields, locals, dotted);
        }
        Set<String> candidates = new TreeSet<>();
        if (scope == Scope.FIELDS_AND_LOCALS) {
            candidates.addAll(fields);
            candidates.addAll(locals);
        } else {
            locals.stream().filter(n -> !fields.contains(n)).forEach(candidates::add);
        }
        candidates.removeAll(typeNames);
        // A name used as "x.name" that is not a detected field may be an undetected field or a library
        // member; renaming only its bare occurrences would break the code, so leave it alone.
        candidates.removeIf(name -> dotted.contains(name) && !(scope == Scope.FIELDS_AND_LOCALS && fields.contains(name)));

        Map<String, String> renames = new LinkedHashMap<>();
        Set<String> taken = new HashSet<>(allIdents);
        taken.addAll(JavaLexer.KEYWORDS);
        for (String name : candidates) {
            renames.put(name, freshName(name, taken, rnd));
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Token>> e : lexed.entrySet()) {
            out.put(e.getKey(), apply(e.getValue(), renames, scope == Scope.FIELDS_AND_LOCALS ? fields : Set.of()));
        }
        return new Result(out, renames);
    }

    /**
     * Finds declarations: an identifier preceded by a type and followed by = ; , ) : or [,
     * plus further declarators of the same statement ({@code Node next, prev;}).
     */
    private static void collect(List<Token> code, Set<String> allIdents, Set<String> typeNames,
                                Set<String> fields, Set<String> locals, Set<String> dotted) {
        Deque<Boolean> frames = new ArrayDeque<>(); // true = type body
        boolean pendingType = false;
        int paren = 0;
        int declParen = -1;        // paren depth of the declaration statement in progress, -1 if none
        boolean declIsField = false;
        for (int k = 0; k < code.size(); k++) {
            Token t = code.get(k);
            Token p = k > 0 ? code.get(k - 1) : null;
            Token n = k + 1 < code.size() ? code.get(k + 1) : null;
            if (t.kind() == Kind.IDENT) {
                allIdents.add(t.text());
                if (p != null && p.is(".")) {
                    dotted.add(t.text());
                }
            }
            if ((t.is("class") || t.is("interface") || t.is("enum") || t.is("record")) && (p == null || !p.is("."))) {
                pendingType = true;
                if (n != null && n.kind() == Kind.IDENT) {
                    typeNames.add(n.text());
                }
            } else if (t.is("{")) {
                frames.push(pendingType);
                pendingType = false;
                declParen = -1;
            } else if (t.is("}") && !frames.isEmpty()) {
                frames.pop();
                declParen = -1;
            } else if (t.is(";")) {
                pendingType = false;
                declParen = -1;
            } else if (t.is("(")) {
                paren++;
            } else if (t.is(")")) {
                paren--;
                if (paren < declParen) {
                    declParen = -1;
                }
            }
            if (t.kind() != Kind.IDENT || p == null || n == null) {
                continue;
            }
            boolean typeBefore = p.kind() == Kind.IDENT || p.is(">") || p.is(">>") || p.is(">>>") || p.is("]")
                    || (p.kind() == Kind.KEYWORD && JavaLexer.PRIMITIVE_TYPES.contains(p.text()));
            boolean declAfter = n.is("=") || n.is(";") || n.is(",") || n.is(")") || n.is(":") || n.is("[");
            if (typeBefore && declAfter) {
                // Declared directly in a type body (not inside a parameter list) = field.
                boolean field = !frames.isEmpty() && frames.peek() && paren == 0;
                (field ? fields : locals).add(t.text());
                if (paren == 0 || p.kind() != Kind.IDENT || !n.is(")")) {
                    declParen = paren;
                    declIsField = field;
                }
            } else if (declParen == paren && p.is(",") && declAfter && !n.is(")")) {
                // Another declarator of the same statement: "int a = 1, b;" / "Node next, prev;"
                (declIsField ? fields : locals).add(t.text());
            }
        }
    }

    private static String apply(List<Token> tokens, Map<String, String> renames, Set<String> dottedOk) {
        StringBuilder sb = new StringBuilder();
        Token prevCode = null;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            String text = t.text();
            if (t.kind() == Kind.IDENT && renames.containsKey(text)) {
                Token next = nextCode(tokens, i);
                boolean afterDot = prevCode != null && prevCode.is(".");
                boolean blocked = (afterDot && !dottedOk.contains(text))
                        || (prevCode != null && (prevCode.is("::") || prevCode.is("@")))
                        || (next != null && next.is("("));
                if (!blocked) {
                    text = renames.get(text);
                }
            }
            sb.append(text);
            if (!t.trivia()) {
                prevCode = t;
            }
        }
        return sb.toString();
    }

    private static Token nextCode(List<Token> tokens, int i) {
        for (int j = i + 1; j < tokens.size(); j++) {
            if (!tokens.get(j).trivia()) {
                return tokens.get(j);
            }
        }
        return null;
    }

    private static String freshName(String name, Set<String> taken, Random rnd) {
        List<String> options = new ArrayList<>(SYNONYMS.getOrDefault(name, List.of()));
        Collections.shuffle(options, rnd);
        List<String> pool = new ArrayList<>(POOL);
        Collections.shuffle(pool, rnd);
        options.addAll(pool);
        for (String o : options) {
            if (taken.add(o)) {
                return o;
            }
        }
        for (int k = 2; ; k++) {
            if (taken.add(name + k)) {
                return name + k;
            }
        }
    }
}
