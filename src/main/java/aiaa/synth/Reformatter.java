package aiaa.synth;

import aiaa.pdm.JavaLexer;
import aiaa.pdm.JavaLexer.Kind;
import aiaa.pdm.JavaLexer.Token;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Re-lays out Java code in a different style (indentation, brace placement, operator
 * spacing, blank lines) while keeping the exact sequence of code tokens and all comments.
 * Spaces are only added or removed between tokens that cannot merge, so the token stream
 * is preserved; every result is still compile-checked by the generator.
 */
final class Reformatter {

    record Style(String indent, boolean allman, boolean spaceOps) {
        String describe() {
            String ind = indent.equals("\t") ? "tabs" : indent.length() + "-space";
            return ind + " indent, " + (allman ? "Allman" : "K&R") + " braces, "
                    + (spaceOps ? "spaced" : "compact") + " operators";
        }
    }

    private static final Set<String> BINARY = Set.of("=", "==", "!=", "<", ">", "<=", ">=", "+", "-", "*", "/",
            "%", "&&", "||", "&", "|", "^", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>=",
            "<<", ">>", ">>>", "?", ":", "->");
    private static final Set<String> ALWAYS_SPACED = Set.of("=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=",
            "<<=", ">>=", ">>>=", "->");
    private static final Set<String> KEYWORD_PAREN = Set.of("if", "for", "while", "switch", "catch",
            "synchronized", "return", "try");
    private static final String OP_CHARS = "+-*/%<>=!&|^~?:";

    private Reformatter() {
    }

    static Style randomStyle(Random rnd) {
        String[] indents = {"  ", "   ", "    ", "\t"};
        return new Style(indents[rnd.nextInt(indents.length)], rnd.nextBoolean(), rnd.nextBoolean());
    }

    static String format(String src, Style style) {
        return new Printer(JavaLexer.lex(src), style).print();
    }

    private static final class Printer {
        private final List<Token> tokens;
        private final Style style;
        private final StringBuilder out = new StringBuilder();
        private final Deque<Character> frames = new ArrayDeque<>(); // C type body, B block, D do-block, I inline
        private int depth;
        private int paren;
        private int inline;
        private boolean lineStart = true;
        private Token lineFirst;
        private Token prev;
        private boolean prevSpacedOp;
        private boolean sameLineAfterBrace;
        private boolean pendingType;

        Printer(List<Token> tokens, Style style) {
            this.tokens = tokens;
            this.style = style;
        }

        String print() {
            for (int i = 0; i < tokens.size(); i++) {
                Token t = tokens.get(i);
                if (t.kind() == Kind.WHITESPACE) {
                    continue;
                }
                if (t.comment()) {
                    comment(i, t);
                    continue;
                }
                code(i, t);
            }
            newline();
            return out.toString();
        }

        private void comment(int i, Token t) {
            boolean ownLine = i == 0 || (tokens.get(i - 1).kind() == Kind.WHITESPACE
                    && tokens.get(i - 1).text().contains("\n"));
            if (ownLine) {
                newline();
            }
            emit(t, !ownLine);
            boolean newlineAfter = t.kind() == Kind.LINE_COMMENT
                    || (i + 1 < tokens.size() && tokens.get(i + 1).kind() == Kind.WHITESPACE
                    && tokens.get(i + 1).text().contains("\n"));
            if (newlineAfter) {
                newline();
            }
        }

        private void code(int i, Token t) {
            boolean spacedOp = false;
            if (t.is("{")) {
                boolean isInline = paren > 0 || inline > 0
                        || (prev != null && (prev.is("=") || prev.is("]")));
                if (isInline) {
                    emit(t, prev != null && !prev.is("(") && !prev.is("{"));
                    frames.push('I');
                    inline++;
                } else {
                    char kind = pendingType ? 'C' : (prev != null && prev.is("do") ? 'D' : 'B');
                    pendingType = false;
                    if (style.allman()) {
                        newline();
                    }
                    emit(t, true);
                    frames.push(kind);
                    depth++;
                    newline();
                }
            } else if (t.is("}")) {
                char kind = frames.isEmpty() ? 'B' : frames.pop();
                if (kind == 'I') {
                    inline--;
                    emit(t, prev != null && !prev.is("{"));
                } else {
                    depth = Math.max(0, depth - 1);
                    newline();
                    emit(t, false);
                    Token next = nextCode(i);
                    if (next == null) {
                        newline();
                    } else if (next.is(";") || next.is(",") || next.is(")") || next.is(".")) {
                        // stays on this line: "};", "}).", ...
                    } else if (next.is("else") || next.is("catch") || next.is("finally")
                            || (kind == 'D' && next.is("while"))) {
                        if (style.allman()) {
                            newline();
                        } else {
                            sameLineAfterBrace = true;
                        }
                    } else {
                        newline();
                        boolean memberEnd = !frames.isEmpty() && frames.peek() == 'C';
                        if ((memberEnd || frames.isEmpty()) && !next.is("}")) {
                            blankLine();
                        }
                    }
                }
            } else if (t.is(";")) {
                emit(t, false);
                if (paren == 0 && inline == 0) {
                    newline();
                }
                pendingType = false;
            } else if (t.is("(")) {
                emit(t, needSpace(t));
                paren++;
            } else if (t.is(")")) {
                paren = Math.max(0, paren - 1);
                emit(t, false);
            } else if (t.is(":") && paren == 0 && inline == 0 && lineFirst != null
                    && (lineFirst.is("case") || lineFirst.is("default"))) {
                emit(t, false);
                newline();
            } else {
                spacedOp = isSpacedBinary(t);
                emit(t, needSpace(t));
            }
            if ((t.is("class") || t.is("interface") || t.is("enum") || t.is("record"))
                    && (prev == null || !prev.is("."))) {
                pendingType = true;
            }
            prev = t;
            prevSpacedOp = spacedOp;
        }

        private boolean isBinary(Token t) {
            if (t.kind() != Kind.OPERATOR || !BINARY.contains(t.text()) || prev == null) {
                return false;
            }
            boolean operandEnd = (prev.wordLike() && (prev.kind() != Kind.KEYWORD
                    || Set.of("this", "super", "null", "true", "false").contains(prev.text())))
                    || prev.is(")") || prev.is("]") || prev.is("++") || prev.is("--");
            boolean generic = Set.of("<", ">", ">>", ">>>").contains(t.text()) && prev.kind() == Kind.IDENT
                    && Character.isUpperCase(prev.text().charAt(0));
            return operandEnd && !generic;
        }

        private boolean isSpacedBinary(Token t) {
            return isBinary(t) && (style.spaceOps() || ALWAYS_SPACED.contains(t.text()));
        }

        private boolean needSpace(Token t) {
            if (prev == null) {
                return false;
            }
            if (sameLineAfterBrace || prevSpacedOp || isSpacedBinary(t)) {
                return true;
            }
            if (t.is(";") || t.is(",") || t.is(")") || t.is("]") || t.is(".") || t.is("::") || t.is("[")) {
                return false;
            }
            if (prev.is("(") || prev.is("[") || prev.is(".") || prev.is("@") || prev.is("::")) {
                return false;
            }
            if (prev.is(",") || prev.is(";")) {
                return true;
            }
            if (prev.kind() == Kind.KEYWORD) {
                return !t.is("(") || KEYWORD_PAREN.contains(prev.text());
            }
            if (t.is("(")) {
                return false;
            }
            if (prev.is("<") && t.is(">")) {
                return false;
            }
            if (isOpChars(prev) && isOpChars(t)) {
                return true; // never let two operators merge into one token
            }
            if (prev.wordLike() && (t.wordLike() || t.is("@"))) {
                return true;
            }
            return (prev.is(")") || prev.is("]") || prev.is("}") || prev.is(">") || prev.is(">>")
                    || prev.is(">>>")) && t.wordLike();
        }

        private static boolean isOpChars(Token t) {
            if (t.kind() != Kind.OPERATOR) {
                return false;
            }
            for (char c : t.text().toCharArray()) {
                if (OP_CHARS.indexOf(c) < 0) {
                    return false;
                }
            }
            return true;
        }

        private Token nextCode(int i) {
            for (int j = i + 1; j < tokens.size(); j++) {
                if (!tokens.get(j).trivia()) {
                    return tokens.get(j);
                }
            }
            return null;
        }

        private void emit(Token t, boolean spaceBefore) {
            if (lineStart) {
                out.append(style.indent().repeat(depth));
                lineStart = false;
                lineFirst = t;
            } else if (spaceBefore) {
                out.append(' ');
            }
            sameLineAfterBrace = false;
            out.append(t.text());
        }

        private void newline() {
            if (!lineStart) {
                out.append('\n');
                lineStart = true;
            }
        }

        private void blankLine() {
            newline();
            if (!out.isEmpty() && !out.toString().endsWith("\n\n")) {
                out.append('\n');
            }
        }
    }
}
