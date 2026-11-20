package aiaa.pdm;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Lossless Java lexer: concatenating the token texts reproduces the input exactly
 * (whitespace and comments are kept as trivia tokens). Shared by the token baseline
 * and the synthetic-copy transformations.
 */
public final class JavaLexer {

    public enum Kind { WHITESPACE, LINE_COMMENT, BLOCK_COMMENT, STRING, CHAR, NUMBER, IDENT, KEYWORD, OPERATOR }

    public record Token(Kind kind, String text) {
        public boolean trivia() {
            return kind == Kind.WHITESPACE || kind == Kind.LINE_COMMENT || kind == Kind.BLOCK_COMMENT;
        }

        public boolean comment() {
            return kind == Kind.LINE_COMMENT || kind == Kind.BLOCK_COMMENT;
        }

        /** True for code tokens (keyword / operator / identifier) with exactly this text. */
        public boolean is(String s) {
            return (kind == Kind.KEYWORD || kind == Kind.OPERATOR || kind == Kind.IDENT) && text.equals(s);
        }

        /** Identifier, keyword or literal (tokens that need a space between them). */
        public boolean wordLike() {
            return kind == Kind.IDENT || kind == Kind.KEYWORD || kind == Kind.NUMBER
                    || kind == Kind.STRING || kind == Kind.CHAR;
        }
    }

    public static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "var", "record", "yield");

    /** Keywords that can end a type in a declaration ({@code int x}, {@code var y}). */
    public static final Set<String> PRIMITIVE_TYPES = Set.of(
            "int", "long", "short", "byte", "char", "boolean", "double", "float", "var");

    private static final String[] OPERATORS = {
            ">>>=", "<<=", ">>=", ">>>", "...", "->", "::", "==", "!=", "<=", ">=", "&&", "||",
            "++", "--", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>"};

    private JavaLexer() {
    }

    public static List<Token> lex(String src) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = src.length();
        while (i < n) {
            int start = i;
            char c = src.charAt(i);
            Kind kind;
            if (Character.isWhitespace(c)) {
                while (i < n && Character.isWhitespace(src.charAt(i))) {
                    i++;
                }
                kind = Kind.WHITESPACE;
            } else if (src.startsWith("//", i)) {
                while (i < n && src.charAt(i) != '\n' && src.charAt(i) != '\r') {
                    i++;
                }
                kind = Kind.LINE_COMMENT;
            } else if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                kind = Kind.BLOCK_COMMENT;
            } else if (src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                i = end < 0 ? n : end + 3;
                kind = Kind.STRING;
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < n && src.charAt(i) != c && src.charAt(i) != '\n') {
                    i += src.charAt(i) == '\\' ? 2 : 1;
                }
                i = Math.min(n, i + 1);
                kind = c == '"' ? Kind.STRING : Kind.CHAR;
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1)))) {
                boolean hex = src.startsWith("0x", i) || src.startsWith("0X", i);
                while (i < n) {
                    char d = src.charAt(i);
                    boolean exponentSign = (d == '+' || d == '-') && !hex
                            && (src.charAt(i - 1) == 'e' || src.charAt(i - 1) == 'E');
                    if (Character.isLetterOrDigit(d) || d == '.' || d == '_' || exponentSign) {
                        i++;
                    } else {
                        break;
                    }
                }
                kind = Kind.NUMBER;
            } else if (Character.isJavaIdentifierStart(c)) {
                while (i < n && Character.isJavaIdentifierPart(src.charAt(i))) {
                    i++;
                }
                kind = KEYWORDS.contains(src.substring(start, i)) ? Kind.KEYWORD : Kind.IDENT;
            } else {
                String op = String.valueOf(c);
                for (String candidate : OPERATORS) {
                    if (src.startsWith(candidate, i)) {
                        op = candidate;
                        break;
                    }
                }
                i += op.length();
                kind = Kind.OPERATOR;
            }
            out.add(new Token(kind, src.substring(start, i)));
        }
        return out;
    }

    public static String join(List<Token> tokens) {
        StringBuilder sb = new StringBuilder();
        for (Token t : tokens) {
            sb.append(t.text());
        }
        return sb.toString();
    }

    /** Code tokens only (no whitespace / comments). */
    public static List<Token> significant(List<Token> tokens) {
        return tokens.stream().filter(t -> !t.trivia()).toList();
    }
}
