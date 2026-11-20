package aiaa.synth;

import aiaa.pdm.JavaLexer;
import aiaa.pdm.JavaLexer.Token;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Lightweight structural view of a Java file: top-level type bodies and their members.
 * Works on the lossless token list, so members can be moved or replaced as exact text.
 * Not a full parser: it relies on brace/paren structure, which is enough for student code
 * that compiles (and every transformed result is compile-checked anyway).
 */
final class JavaStructure {

    enum MemberKind { FIELD, METHOD, CONSTRUCTOR, TYPE, INITIALIZER, OTHER }

    /**
     * A member of a type body. Token range [start, end) includes the member's leading
     * whitespace and comments, so moving it keeps its comments attached.
     *
     * @param firstCode index of the member's first code token
     */
    record Member(MemberKind kind, String name, int paramCount, int start, int firstCode, int end) {
        String key(String className) {
            return className + "." + name + "/" + paramCount;
        }
    }

    /** A top-level type body; {@code open}/{@code close} are the indices of its braces. */
    record TypeBody(String name, boolean isEnum, int open, int close, List<Member> members) {
    }

    private JavaStructure() {
    }

    /** Top-level (not nested) type bodies, in file order. */
    static List<TypeBody> topLevelTypes(List<Token> tokens) {
        List<TypeBody> out = new ArrayList<>();
        Deque<int[]> stack = new ArrayDeque<>(); // [openIndex, isType(0/1)]
        String pendingName = null;
        boolean pendingEnum = false;
        boolean awaitingName = false;
        Token prevCode = null;
        List<String> names = new ArrayList<>();
        List<Boolean> enums = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.trivia()) {
                continue;
            }
            if (awaitingName && t.kind() == JavaLexer.Kind.IDENT) {
                pendingName = t.text();
                awaitingName = false;
            } else if ((t.is("class") || t.is("interface") || t.is("enum") || t.is("record"))
                    && (prevCode == null || !prevCode.is("."))) {
                awaitingName = true;
                pendingEnum = t.is("enum");
            } else if (t.is("{")) {
                boolean isType = pendingName != null;
                stack.push(new int[]{i, isType ? 1 : 0});
                names.add(isType ? pendingName : null);
                enums.add(pendingEnum);
                pendingName = null;
                pendingEnum = false;
            } else if (t.is("}") && !stack.isEmpty()) {
                int[] frame = stack.pop();
                String name = names.removeLast();
                boolean isEnum = enums.removeLast();
                if (frame[1] == 1 && stack.isEmpty()) {
                    out.add(new TypeBody(name, isEnum, frame[0], i,
                            isEnum ? List.of() : members(tokens, frame[0], i, name)));
                }
            } else if (t.is(";")) {
                pendingName = null;
                awaitingName = false;
            }
            prevCode = t;
        }
        return out;
    }

    private static List<Member> members(List<Token> tokens, int open, int close, String className) {
        List<Member> out = new ArrayList<>();
        int memberStart = open + 1;
        int firstCode = -1;
        int depth = 0;
        int paren = 0;
        boolean sawAssign = false;
        for (int i = open + 1; i < close; i++) {
            Token t = tokens.get(i);
            if (t.trivia()) {
                continue;
            }
            if (firstCode < 0) {
                firstCode = i;
            }
            if (t.is("(")) {
                paren++;
            } else if (t.is(")")) {
                paren--;
            } else if (t.is("{")) {
                depth++;
            } else if (t.is("}")) {
                depth--;
            } else if (t.is("=") && depth == 0 && paren == 0) {
                sawAssign = true;
            }
            boolean endsAtSemicolon = t.is(";") && depth == 0 && paren == 0;
            boolean endsAtBrace = t.is("}") && depth == 0 && paren == 0 && !sawAssign;
            if (endsAtSemicolon || endsAtBrace) {
                out.add(classify(tokens, memberStart, firstCode, i + 1, className));
                memberStart = i + 1;
                firstCode = -1;
                sawAssign = false;
            }
        }
        return out;
    }

    private static Member classify(List<Token> tokens, int start, int firstCode, int end, String className) {
        List<Integer> code = new ArrayList<>();
        for (int i = firstCode; i < end; i++) {
            if (!tokens.get(i).trivia()) {
                code.add(i);
            }
        }
        Token first = tokens.get(code.getFirst());
        if (first.is("{") || (first.is("static") && code.size() > 1 && tokens.get(code.get(1)).is("{"))) {
            return new Member(MemberKind.INITIALIZER, "", 0, start, firstCode, end);
        }
        if (first.is(";")) {
            return new Member(MemberKind.OTHER, "", 0, start, firstCode, end);
        }
        for (int k = 0; k < code.size(); k++) {
            Token t = tokens.get(code.get(k));
            if (t.is("@") && k + 1 < code.size()) {
                // Skip an annotation: @Name, @a.b.Name, optionally followed by (...)
                k++;
                while (k + 2 < code.size() && tokens.get(code.get(k + 1)).is(".")) {
                    k += 2;
                }
                if (k + 1 < code.size() && tokens.get(code.get(k + 1)).is("(")) {
                    int depth = 0;
                    for (k = k + 1; k < code.size(); k++) {
                        Token a = tokens.get(code.get(k));
                        if (a.is("(")) {
                            depth++;
                        } else if (a.is(")") && --depth == 0) {
                            break;
                        }
                    }
                }
                continue;
            }
            if (t.is("{") || t.is("=")) {
                break;
            }
            if ((t.is("class") || t.is("interface") || t.is("enum") || t.is("record")) && k + 1 < code.size()) {
                return new Member(MemberKind.TYPE, tokens.get(code.get(k + 1)).text(), 0, start, firstCode, end);
            }
            if (t.is("(") && k > 0 && tokens.get(code.get(k - 1)).kind() == JavaLexer.Kind.IDENT) {
                String name = tokens.get(code.get(k - 1)).text();
                int params = countParams(tokens, code, k);
                MemberKind kind = name.equals(className) ? MemberKind.CONSTRUCTOR : MemberKind.METHOD;
                return new Member(kind, name, params, start, firstCode, end);
            }
        }
        return new Member(MemberKind.FIELD, fieldName(tokens, code), 0, start, firstCode, end);
    }

    private static int countParams(List<Token> tokens, List<Integer> code, int openParen) {
        int depth = 0;
        int angle = 0;
        int commas = 0;
        boolean any = false;
        for (int k = openParen; k < code.size(); k++) {
            Token t = tokens.get(code.get(k));
            if (t.is("(")) {
                depth++;
                if (depth == 1) {
                    continue;
                }
            } else if (t.is(")")) {
                depth--;
                if (depth == 0) {
                    break;
                }
            } else if (t.is("<")) {
                angle++;
            } else if (t.is(">")) {
                angle--;
            } else if (t.is(">>")) {
                angle -= 2;
            } else if (t.is(",") && depth == 1 && angle <= 0) {
                commas++;
            }
            any = true;
        }
        return any ? commas + 1 : 0;
    }

    private static String fieldName(List<Token> tokens, List<Integer> code) {
        String last = "";
        for (int idx : code) {
            Token t = tokens.get(idx);
            if (t.is("=") || t.is(";")) {
                break;
            }
            if (t.kind() == JavaLexer.Kind.IDENT) {
                last = t.text();
            }
        }
        return last;
    }

    /** The whitespace that indents the line containing token {@code index}. */
    static String indentBefore(List<Token> tokens, int index) {
        if (index > 0 && tokens.get(index - 1).kind() == JavaLexer.Kind.WHITESPACE) {
            String ws = tokens.get(index - 1).text();
            int nl = Math.max(ws.lastIndexOf('\n'), ws.lastIndexOf('\r'));
            if (nl >= 0) {
                return ws.substring(nl + 1);
            }
        }
        return "";
    }

    static String text(List<Token> tokens, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(tokens.get(i).text());
        }
        return sb.toString();
    }

    /** Code tokens of a range joined by single spaces (layout- and comment-insensitive). */
    static String codeSignature(List<Token> tokens, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (!tokens.get(i).trivia()) {
                sb.append(tokens.get(i).text()).append(' ');
            }
        }
        return sb.toString();
    }
}
