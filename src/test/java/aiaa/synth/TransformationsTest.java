package aiaa.synth;

import aiaa.pdm.JavaLexer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransformationsTest {

    /** Deliberately awkward but valid code: generics, lambdas, switch, do-while, arrays, text block. */
    static final String LIST = """
            import java.util.*;
            import java.util.function.Function;

            public class MyLinkedList<T> implements Iterable<T> {
                private ListNode head;   // dummy head
                private int size;
                private final int[] weights = {1, 2, 3};

                /** Inner node class. */
                private class ListNode {
                    private T data;
                    private ListNode next;
                    private ListNode(T d) { this.data = d; this.next = null; }
                }

                public MyLinkedList() {
                    this.head = new ListNode(null); // with a dummy head node
                    this.size = 0;
                }

                // Add e to the front
                public void addFirst(T e) {
                    ListNode node = new ListNode(e);
                    node.next = head.next;
                    head.next = node;
                    size++;
                }

                /* removes the first element */
                public T removeFirst() throws Exception {
                    if (size == 0) {
                        throw new Exception("empty");
                    } else {
                        ListNode first = head.next;
                        head.next = first.next;
                        size--;
                        return first.data;
                    }
                }

                public int indexOf(Object o) {
                    int i = 0;
                    for (ListNode cur = head.next; cur != null; cur = cur.next, i++) {
                        if (o == null ? cur.data == null : o.equals(cur.data)) return i;
                    }
                    return -1;
                }

                @SuppressWarnings("unchecked")
                public int classify(int x) {
                    int[] arr = new int[]{x, -x, x >> 1};
                    int result;
                    switch (arr.length) {
                        case 3:
                            result = arr[0] - -arr[1];
                            break;
                        default:
                            result = 0;
                    }
                    int k = 0;
                    do {
                        k += weights[k % 3];
                    } while (k < 5 && !(k == 4));
                    Function<Integer, Integer> twice = v -> { return v * 2; };
                    String text = \"""
                        multi-line
                        text block\""";
                    return result + twice.apply(k) + text.length();
                }

                public Iterator<T> iterator() {
                    List<T> items = new ArrayList<>();
                    for (ListNode cur = head.next; cur != null; cur = cur.next) items.add(cur.data);
                    return items.iterator();
                }
            }
            """;

    static final String TESTER = """
            public class MyLinkedListTester {
                public static void main(String[] args) throws Exception {
                    MyLinkedList<String> list = new MyLinkedList<>();
                    list.addFirst("a");
                    System.out.println(list.indexOf("a") + " " + list.removeFirst() + " " + list.classify(4));
                }
            }
            """;

    static Map<String, String> program() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("MyLinkedList.java", LIST);
        files.put("MyLinkedListTester.java", TESTER);
        return files;
    }

    private static List<String> code(String src) {
        return JavaLexer.significant(JavaLexer.lex(src)).stream().map(JavaLexer.Token::text).toList();
    }

    @Test
    void sampleProgramCompiles() {
        CompileCheck.Result r = CompileCheck.compile(program());
        assertTrue(r.ok(), r.diagnostics());
    }

    @Test
    void lexerIsLossless() {
        assertEquals(LIST, JavaLexer.join(JavaLexer.lex(LIST)));
    }

    @Test
    void everyReformatStyleKeepsCodeTokensAndCompiles() {
        for (String indent : List.of("  ", "   ", "    ", "\t")) {
            for (boolean allman : List.of(true, false)) {
                for (boolean spaceOps : List.of(true, false)) {
                    Reformatter.Style style = new Reformatter.Style(indent, allman, spaceOps);
                    Map<String, String> out = new LinkedHashMap<>();
                    program().forEach((k, v) -> out.put(k, Reformatter.format(v, style)));
                    assertEquals(code(LIST), code(out.get("MyLinkedList.java")), style.describe());
                    assertNotEquals(LIST, out.get("MyLinkedList.java"));
                    CompileCheck.Result r = CompileCheck.compile(out);
                    assertTrue(r.ok(), style.describe() + "\n" + r.diagnostics() + "\n" + out.get("MyLinkedList.java"));
                }
            }
        }
    }

    @Test
    void renameChangesVariablesButNotApiAndCompiles() {
        Renamer.Result r = Renamer.rename(program(), Renamer.Scope.FIELDS_AND_LOCALS, new Random(1));
        String out = r.files().get("MyLinkedList.java");
        assertTrue(r.renames().containsKey("head"), r.renames().toString());
        assertTrue(r.renames().containsKey("cur"));
        assertFalse(r.renames().containsKey("addFirst"), "method names are never renamed");
        assertFalse(r.renames().containsKey("ListNode"), "type names are never renamed");
        assertFalse(code(out).contains("head"), "field renamed everywhere in code (comments are left alone)");
        assertTrue(r.renames().containsKey("arr"));
        assertTrue(out.contains(r.renames().get("arr") + ".length"), "library member .length untouched");
        assertTrue(out.contains("text.length()") || out.contains(r.renames().get("text") + ".length()"));
        assertTrue(out.contains("public void addFirst("));
        CompileCheck.Result c = CompileCheck.compile(r.files());
        assertTrue(c.ok(), c.diagnostics() + "\n" + out);
    }

    @Test
    void renameHandlesMultipleDeclaratorsPerStatement() {
        String src = """
                public class CDoublyLinkedList {
                    private class Node {
                        private Object data;
                        private Node next, prev;
                        private Node(Object d, Node p, Node n) { this.data = d; this.prev = p; this.next = n; }
                    }
                    private Node head;
                    public int count() {
                        int total = 0, steps = 0;
                        Node prev = head.prev;
                        for (Node cur = head.next; cur != head; cur = cur.next) { total++; steps += 2; }
                        return total + steps + (prev == null ? 0 : 1);
                    }
                }
                """;
        for (Renamer.Scope scope : Renamer.Scope.values()) {
            Renamer.Result r = Renamer.rename(Map.of("CDoublyLinkedList.java", src), scope, new Random(9));
            CompileCheck.Result c = CompileCheck.compile(r.files());
            assertTrue(c.ok(), scope + "\n" + c.diagnostics() + r.files().get("CDoublyLinkedList.java"));
            assertTrue(r.renames().containsKey("steps"), scope + " " + r.renames());
        }
    }

    @Test
    void localsOnlyRenameLeavesFieldsAlone() {
        Renamer.Result r = Renamer.rename(program(), Renamer.Scope.LOCALS_ONLY, new Random(2));
        assertFalse(r.renames().containsKey("head"));
        assertTrue(r.renames().containsKey("cur"));
        assertTrue(CompileCheck.compiles(r.files()));
    }

    @Test
    void commentEditsTouchOnlyComments() {
        CommentEditor.Result r = CommentEditor.edit(program(), new Random(3));
        assertTrue(r.changed());
        assertEquals(code(LIST), code(r.files().get("MyLinkedList.java")));
        assertTrue(CompileCheck.compiles(r.files()));
    }

    @Test
    void reorderPermutesMethodsKeepsFieldsAndCompiles() {
        MethodReorderer.Result r = MethodReorderer.reorder(program(), new Random(4));
        assertTrue(r.changed());
        String out = r.files().get("MyLinkedList.java");
        assertNotEquals(LIST, out);
        // Fields stay first, the same members are present, comments travel with their method.
        assertTrue(out.indexOf("private ListNode head;") < out.indexOf("public MyLinkedList()"));
        List<String> before = new ArrayList<>(code(LIST));
        List<String> after = new ArrayList<>(code(out));
        before.sort(null);
        after.sort(null);
        assertEquals(before, after);
        assertTrue(out.contains("/* removes the first element */\n    public T removeFirst()"));
        assertTrue(CompileCheck.compiles(r.files()));
    }
}
