package aiaa.synth;

import aiaa.pdm.JavaLexer;
import aiaa.pdm.JavaLexer.Token;
import aiaa.pdm.SourceView;
import aiaa.pdm.StarterCodeFilter;
import aiaa.synth.JavaStructure.Member;
import aiaa.synth.JavaStructure.MemberKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Partial copying: replaces some of a host submission's methods with the source's versions
 * of the same methods (same class, name and parameter count).
 *
 * <p>Amount copied = fraction of the variant's substantive, non-starter lines (excluding
 * excluded files such as testers) that came from the source. The subset of methods whose
 * fraction is closest to the target, and that still compiles, is chosen.
 */
final class MethodSplicer {

    record Result(Map<String, String> files, double fraction, List<String> methods, int compileAttempts) {
    }

    private record Located(String file, List<Token> tokens, Member member) {
        String text() {
            return JavaStructure.text(tokens, member.start(), member.end());
        }

        String signature() {
            return JavaStructure.codeSignature(tokens, member.firstCode(), member.end());
        }
    }

    private static final int MAX_CANDIDATES = 14;
    private static final int MAX_COMPILE_ATTEMPTS = 25;
    static final double TOLERANCE = 0.12;

    private MethodSplicer() {
    }

    static Optional<Result> splice(Map<String, String> source, Map<String, String> host, double target,
                                   StarterCodeFilter filter, Set<String> excludedFiles, Random rnd,
                                   Predicate<Map<String, String>> compiles) {
        Map<String, Located> src = methods(source, excludedFiles);
        Map<String, Located> dst = methods(host, excludedFiles);
        List<String> candidates = new ArrayList<>();
        for (String key : dst.keySet()) {
            Located s = src.get(key);
            if (s != null && !s.signature().equals(dst.get(key).signature()) && ownLines(s.text(), filter) > 0) {
                candidates.add(key);
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Collections.shuffle(candidates, rnd);
        if (candidates.size() > MAX_CANDIDATES) {
            candidates = new ArrayList<>(candidates.subList(0, MAX_CANDIDATES));
        }
        Collections.sort(candidates);

        // Additive estimate for every subset, then verify the best ones exactly.
        int hostTotal = ownLines(host, excludedFiles, filter);
        int[] s = new int[candidates.size()];
        int[] h = new int[candidates.size()];
        for (int k = 0; k < candidates.size(); k++) {
            s[k] = ownLines(src.get(candidates.get(k)).text(), filter);
            h[k] = ownLines(dst.get(candidates.get(k)).text(), filter);
        }
        List<long[]> ranked = new ArrayList<>(); // [mask, scaledError]
        for (long mask = 1; mask < (1L << candidates.size()); mask++) {
            int copied = 0;
            int total = hostTotal;
            for (int k = 0; k < candidates.size(); k++) {
                if ((mask & (1L << k)) != 0) {
                    copied += s[k];
                    total += s[k] - h[k];
                }
            }
            double est = total <= 0 ? 0 : (double) copied / total;
            ranked.add(new long[]{mask, Math.round(Math.abs(est - target) * 1_000_000)});
        }
        ranked.sort(Comparator.comparingLong(a -> a[1]));

        int attempts = 0;
        for (long[] r : ranked) {
            if (attempts >= MAX_COMPILE_ATTEMPTS || r[1] > TOLERANCE * 2 * 1_000_000) {
                break;
            }
            List<String> chosen = new ArrayList<>();
            for (int k = 0; k < candidates.size(); k++) {
                if ((r[0] & (1L << k)) != 0) {
                    chosen.add(candidates.get(k));
                }
            }
            Built built = build(host, dst, src, chosen);
            double exact = exactFraction(built, excludedFiles, filter);
            if (Math.abs(exact - target) > TOLERANCE) {
                continue;
            }
            attempts++;
            if (compiles.test(built.files())) {
                return Optional.of(new Result(built.files(), exact, chosen, attempts));
            }
        }
        return Optional.empty();
    }

    private record Built(Map<String, String> files, Map<String, List<int[]>> copiedLines) {
    }

    private static Built build(Map<String, String> host, Map<String, Located> dst, Map<String, Located> src,
                               List<String> chosen) {
        Map<String, String> files = new LinkedHashMap<>();
        Map<String, List<int[]>> copied = new HashMap<>();
        for (Map.Entry<String, String> e : host.entrySet()) {
            List<Located> replace = new ArrayList<>();
            for (String key : chosen) {
                if (dst.get(key).file().equals(e.getKey())) {
                    replace.add(dst.get(key));
                }
            }
            if (replace.isEmpty()) {
                files.put(e.getKey(), e.getValue());
                continue;
            }
            replace.sort(Comparator.comparingInt(l -> l.member().start()));
            List<Token> tokens = replace.getFirst().tokens();
            StringBuilder sb = new StringBuilder();
            List<int[]> ranges = new ArrayList<>();
            int cursor = 0;
            for (Located l : replace) {
                sb.append(JavaStructure.text(tokens, cursor, l.member().start()));
                int startLine = lineOf(sb) ;
                String key = keyOf(dst, l);
                sb.append(src.get(key).text());
                ranges.add(new int[]{startLine, lineOf(sb)});
                cursor = l.member().end();
            }
            sb.append(JavaStructure.text(tokens, cursor, tokens.size()));
            files.put(e.getKey(), sb.toString());
            copied.put(e.getKey(), ranges);
        }
        return new Built(files, copied);
    }

    private static String keyOf(Map<String, Located> dst, Located l) {
        for (Map.Entry<String, Located> e : dst.entrySet()) {
            if (e.getValue() == l) {
                return e.getKey();
            }
        }
        throw new IllegalStateException();
    }

    /** 1-based number of the line the next appended character will be on. */
    private static int lineOf(CharSequence sb) {
        int lines = 1;
        for (int i = 0; i < sb.length(); i++) {
            if (sb.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private static double exactFraction(Built built, Set<String> excludedFiles, StarterCodeFilter filter) {
        Map<String, String> counted = new LinkedHashMap<>(built.files());
        counted.keySet().removeIf(excludedFiles::contains);
        SourceView view = SourceView.of("variant", counted);
        if (filter != null) {
            view = filter.filter(view).view();
        }
        int total = 0;
        int fromSource = 0;
        for (SourceView.FileView f : view.files()) {
            List<int[]> ranges = built.copiedLines().getOrDefault(f.name(), List.of());
            for (SourceView.Line line : f.lines()) {
                if (!isSubstantive(line.text())) {
                    continue;
                }
                total++;
                for (int[] r : ranges) {
                    if (line.number() >= r[0] && line.number() <= r[1]) {
                        fromSource++;
                        break;
                    }
                }
            }
        }
        return total == 0 ? 0 : (double) fromSource / total;
    }

    private static boolean isSubstantive(String line) {
        return line.chars().anyMatch(Character::isLetterOrDigit);
    }

    private static int ownLines(String text, StarterCodeFilter filter) {
        SourceView v = SourceView.of("m", Map.of("M.java", text));
        return (filter == null ? v : filter.filter(v).view()).substantiveLineCount();
    }

    private static int ownLines(Map<String, String> files, Set<String> excludedFiles, StarterCodeFilter filter) {
        Map<String, String> counted = new LinkedHashMap<>(files);
        counted.keySet().removeIf(excludedFiles::contains);
        SourceView v = SourceView.of("h", counted);
        return (filter == null ? v : filter.filter(v).view()).substantiveLineCount();
    }

    /** Methods and constructors of top-level classes, keyed by Class.name/paramCount (first overload wins). */
    private static Map<String, Located> methods(Map<String, String> files, Set<String> excludedFiles) {
        Map<String, Located> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (excludedFiles.contains(e.getKey())) {
                continue;
            }
            List<Token> tokens = JavaLexer.lex(e.getValue());
            for (JavaStructure.TypeBody type : JavaStructure.topLevelTypes(tokens)) {
                for (Member m : type.members()) {
                    if (m.kind() == MemberKind.METHOD || m.kind() == MemberKind.CONSTRUCTOR) {
                        out.putIfAbsent(m.key(type.name()), new Located(e.getKey(), tokens, m));
                    }
                }
            }
        }
        return out;
    }
}
