package aiaa.synth;

import aiaa.llm.MockAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticGeneratorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A second, independently written implementation with the same API (used as splice host). */
    static final String OTHER = """
            import java.util.*;
            import java.util.function.Function;

            public class MyLinkedList<T> implements Iterable<T> {
                private ListNode head;
                private int size;
                private final int[] weights = {1, 2, 3};
                private class ListNode {
                    private T data;
                    private ListNode next;
                    private ListNode(T d) { data = d; }
                }
                public MyLinkedList() { head = new ListNode(null); }
                public void addFirst(T e) {
                    ListNode n = new ListNode(e);
                    n.next = head.next;
                    head.next = n;
                    size = size + 1;
                }
                public T removeFirst() throws Exception {
                    if (head.next == null) throw new Exception("no elements");
                    T value = head.next.data;
                    head.next = head.next.next;
                    size -= 1;
                    return value;
                }
                public int indexOf(Object o) {
                    ListNode p = head.next;
                    int pos = 0;
                    while (p != null) {
                        if (Objects.equals(o, p.data)) {
                            return pos;
                        }
                        p = p.next;
                        pos++;
                    }
                    return -1;
                }
                public int classify(int x) {
                    return x > 0 ? 1 : 0;
                }
                public Iterator<T> iterator() {
                    ArrayList<T> out = new ArrayList<>();
                    ListNode p = head.next;
                    while (p != null) { out.add(p.data); p = p.next; }
                    return out.iterator();
                }
            }
            """;

    @Test
    void spliceHitsTargetFractionAndCompiles() {
        Map<String, String> host = new LinkedHashMap<>(TransformationsTest.program());
        host.put("MyLinkedList.java", OTHER);
        for (double target : List.of(0.25, 0.5, 0.75)) {
            Optional<MethodSplicer.Result> r = MethodSplicer.splice(TransformationsTest.program(), host, target, null,
                    Set.of("MyLinkedListTester.java"), new Random(5), CompileCheck::compiles);
            assertTrue(r.isPresent(), "target " + target);
            assertTrue(Math.abs(r.get().fraction() - target) <= MethodSplicer.TOLERANCE,
                    target + " -> " + r.get().fraction());
            assertFalse(r.get().methods().isEmpty());
        }
    }

    @Test
    void controlFlowRewriteRetriesWithCompilerErrors() {
        AtomicInteger calls = new AtomicInteger();
        MockAdapter mock = new MockAdapter("mock-cf", req -> {
            int n = calls.incrementAndGet();
            String content = n == 1
                    ? TransformationsTest.LIST.replace("return -1;", "return -1") // syntax error
                    : TransformationsTest.LIST.replace("if (size == 0) {", "if (!(size != 0)) {");
            if (n > 1) {
                assertTrue(req.userPrompt().contains("did not compile"), "errors fed back");
            }
            ObjectNode root = JSON.createObjectNode();
            root.putArray("changes").add("removeFirst: condition negated twice");
            root.putArray("files").addObject().put("name", "MyLinkedList.java").put("content", content);
            return root.toString();
        });
        ControlFlowRewriter rw = new ControlFlowRewriter(mock, 1000, null, 3);
        ControlFlowRewriter.Outcome o = rw.rewrite(Map.of("MyLinkedList.java", TransformationsTest.LIST),
                Map.of("MyLinkedListTester.java", TransformationsTest.TESTER));
        assertTrue(o.ok(), o.error());
        assertEquals(2, o.attempts());
        assertTrue(o.files().get("MyLinkedList.java").contains("if (!(size != 0))"));
    }

    @Test
    void endToEndGeneratesCompilingVariantsWithProvenance(@TempDir Path dir) throws IOException {
        Path subs = dir.resolve("F25Hw1");
        write(subs.resolve("1_aaa_1/MyLinkedList.java"), TransformationsTest.LIST);
        write(subs.resolve("1_aaa_1/MyLinkedListTester.java"), TransformationsTest.TESTER);
        write(subs.resolve("2_bbb_2/Doe_MyLinkedList.java"), OTHER);
        write(subs.resolve("2_bbb_2/MyLinkedListTester.java"), TransformationsTest.TESTER);
        write(subs.resolve("3_ccc_3/MyLinkedList.java"), "public class MyLinkedList { broken"); // does not compile
        MockAdapter mock = new MockAdapter("mock-cf", req -> {
            ObjectNode root = JSON.createObjectNode();
            root.putArray("changes").add("loop converted");
            root.putArray("files").addObject().put("name", "MyLinkedList.java").put("content",
                    req.userPrompt().contains("while (p != null)")
                            ? OTHER.replace("return x > 0 ? 1 : 0;", "if (x > 0) return 1; return 0;")
                            : TransformationsTest.LIST.replace("if (size == 0) {", "if (!(size != 0)) {"));
            return root.toString();
        });
        Path out = dir.resolve("synthetic-data/F25Hw1");
        SynthConfig config = new SynthConfig("F25Hw1", subs, List.of(), Set.of("MyLinkedListTester.java"), List.of(),
                2, 7, out, dir.resolve("private/map.csv"), 1000, null, 2);
        SyntheticGenerator.Summary s = new SyntheticGenerator(config, mock).run();

        assertEquals(3, s.originals());
        assertEquals(2, s.originalsCompiling());
        assertEquals(16, s.variants().size(), "2 sources x 8 transformation types");
        assertEquals(16, s.ok(), s.variants().stream().filter(v -> v.status() != SyntheticGenerator.Status.OK)
                .map(v -> v.transformation() + ": " + v.note()).toList().toString());
        for (SyntheticGenerator.Variant v : s.variants()) {
            Path vdir = out.resolve(v.id());
            assertTrue(Files.isDirectory(vdir), v.id());
            if (v.transformation() == SyntheticGenerator.Transformation.EXACT) {
                assertEquals(100.0, v.jaccardVsSource(), 1e-9);
            }
            if (v.transformation() == SyntheticGenerator.Transformation.PARTIAL) {
                assertTrue(v.hostId() != null && !v.hostId().equals(v.sourceId()));
                assertTrue(v.amountCopied() > 0 && v.amountCopied() < 1);
            }
        }
        assertTrue(Files.exists(out.resolve("F25Hw1-S02/MyLinkedList.java")), "display name, no 'Doe_'");
        String manifest = Files.readString(out.resolve(SyntheticGenerator.MANIFEST_CSV));
        assertFalse(manifest.contains("aaa_1"), "folder names stay in the private map only");
        assertTrue(Files.readString(dir.resolve("private/map.csv")).contains("F25Hw1-S01,1_aaa_1"));

        // Same seed -> same dataset.
        String first = Files.readString(out.resolve("F25Hw1-V03/MyLinkedList.java"));
        new SyntheticGenerator(config, mock).run();
        assertEquals(first, Files.readString(out.resolve("F25Hw1-V03/MyLinkedList.java")));
    }

    @Test
    void refusesToOverwriteForeignFolder(@TempDir Path dir) throws IOException {
        write(dir.resolve("out/important.txt"), "keep me");
        Files.createDirectories(dir.resolve("subs"));
        SynthConfig config = new SynthConfig("X", dir.resolve("subs"), List.of(), Set.of(), List.of(), 1, 1,
                dir.resolve("out"), null, null, null, 1);
        assertThrows(IllegalArgumentException.class, () -> new SyntheticGenerator(config, null).run());
        assertTrue(Files.exists(dir.resolve("out/important.txt")));
    }

    private static void write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }
}
