package aiaa.synth;

import aiaa.pdm.JavaLexer;
import aiaa.pdm.JavaLexer.Token;
import aiaa.synth.JavaStructure.Member;
import aiaa.synth.JavaStructure.MemberKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Shuffles the order of methods, constructors and nested types inside each top-level
 * class. Fields and initializer blocks stay in place (their order can matter).
 */
final class MethodReorderer {

    record Result(Map<String, String> files, List<String> changes) {
        boolean changed() {
            return !changes.isEmpty();
        }
    }

    private MethodReorderer() {
    }

    static Result reorder(Map<String, String> files, Random rnd) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> changes = new ArrayList<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            List<Token> tokens = JavaLexer.lex(e.getValue());
            StringBuilder sb = new StringBuilder();
            int cursor = 0;
            for (JavaStructure.TypeBody type : JavaStructure.topLevelTypes(tokens)) {
                List<Member> members = type.members();
                List<Integer> movable = new ArrayList<>();
                for (int k = 0; k < members.size(); k++) {
                    MemberKind kind = members.get(k).kind();
                    if (kind == MemberKind.METHOD || kind == MemberKind.CONSTRUCTOR || kind == MemberKind.TYPE) {
                        movable.add(k);
                    }
                }
                if (movable.size() < 2) {
                    continue;
                }
                List<Integer> perm = new ArrayList<>(movable);
                for (int attempt = 0; attempt < 20 && perm.equals(movable); attempt++) {
                    Collections.shuffle(perm, rnd);
                }
                if (perm.equals(movable)) {
                    continue;
                }
                for (int k = 0; k < members.size(); k++) {
                    Member slot = members.get(k);
                    int pos = movable.indexOf(k);
                    Member placed = pos < 0 ? slot : members.get(perm.get(pos));
                    sb.append(JavaStructure.text(tokens, cursor, slot.start()));
                    sb.append(JavaStructure.text(tokens, placed.start(), placed.end()));
                    cursor = slot.end();
                }
                changes.add(type.name() + ": " + names(members, movable) + " -> " + names(members, perm));
            }
            sb.append(JavaStructure.text(tokens, cursor, tokens.size()));
            out.put(e.getKey(), sb.toString());
        }
        return new Result(out, changes);
    }

    private static List<String> names(List<Member> members, List<Integer> order) {
        return order.stream().map(k -> members.get(k).name()).toList();
    }
}
