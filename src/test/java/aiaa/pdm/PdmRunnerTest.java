package aiaa.pdm;

import aiaa.data.DataLayout;
import aiaa.llm.MockAdapter;
import aiaa.llm.ModelAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdmRunnerTest {

    private static final String STARTER = """
            public class MyLinkedList {
                // Please DO NOT change this addFirst() method.
                public void addFirst(Object e) { size++; }
                public Object removeFirst() throws Exception {
                    return null; //change this as you need.
                }
            }
            """;

    private static final String STUDENT_A = """
            public class MyLinkedList {
                // Please DO NOT change this addFirst() method.
                public void addFirst(Object e) { size++; }
                public Object removeFirst() throws Exception {
                    if (size == 0) throw new Exception("empty");
                    ListNode first = head.next;
                    head.next = first.next;
                    size--;
                    return first.data;
                }
            }
            """;

    /** Renamed copy of A. */
    private static final String STUDENT_B = STUDENT_A.replace("first", "node").replace("\"empty\"", "\"none\"");

    private static final String STUDENT_C = """
            public class MyLinkedList {
                // Please DO NOT change this addFirst() method.
                public void addFirst(Object e) { size++; }
                public Object removeFirst() throws Exception {
                    while (true) {
                        try { return pop(); } catch (RuntimeException x) { throw new Exception(x); }
                    }
                }
            }
            """;

    @Test
    void endToEndWithMockAdapter(@TempDir Path dir) throws IOException {
        Path subs = dir.resolve("F25Hw1");
        write(subs.resolve("1_111_aaa_1/MyLinkedList.java"), STUDENT_A);
        write(subs.resolve("1_111_aaa_1/MyLinkedListTester.java"), "class MyLinkedListTester {}");
        write(subs.resolve("2_222_bbb_2/Doe_MyLinkedList.java"), STUDENT_B);
        write(subs.resolve("3_333_ccc_3/MyLinkedList.java"), STUDENT_C);
        write(subs.resolve("4_444_ddd_4/MyLinkedList.java"), STARTER); // starter only -> excluded
        write(subs.resolve("5_555_eee_5/readme.txt"), "no code");         // no java -> excluded
        Path starter = write(dir.resolve("starter/MyLinkedList.java"), STARTER);
        Path ref = write(dir.resolve("refs/gpt/MyLinkedList.java"), STUDENT_C).getParent();
        Path out = dir.resolve("out");
        Path idMap = dir.resolve("private/ids.csv");

        AtomicInteger calls = new AtomicInteger();
        ModelAdapter mock = new MockAdapter("mock-1", req -> {
            calls.incrementAndGet();
            return LlmSimilarityJudge.mockResponder().apply(req);
        });
        PdmRunner.Summary s = new PdmRunner(config(subs, starter, ref, out, idMap, false), mock).run();

        assertEquals(3, s.eligibleSubmissions());
        assertEquals(2, s.excludedSubmissions());
        assertEquals(3, s.peerPairs());
        assertEquals(3 + 3, calls.get(), "3 peer pairs + 3 reference comparisons");
        for (String f : List.of(PdmRunner.COMPARISONS_CSV, PdmRunner.SCORES_CSV, PdmRunner.INVENTORY_CSV,
                PdmRunner.MANIFEST_JSON, PdmRunner.RAW_JSONL)) {
            assertTrue(Files.exists(out.resolve(f)), f);
        }

        String comparisons = Files.readString(out.resolve(PdmRunner.COMPARISONS_CSV));
        assertTrue(comparisons.contains("PEER,TOKEN,F25Hw1-S01,F25Hw1-S02,F25Hw1,F25Hw1,WITHIN,100.00"), "renamed copy found");
        assertTrue(comparisons.contains("AI_REFERENCE,LLM,F25Hw1-S03,REF-gpt,F25Hw1,,REFERENCE,100.00"), "reference copy found");
        assertFalse(comparisons.contains("111_aaa"), "folder names (student IDs) never appear in outputs");
        String inventory = Files.readString(out.resolve(PdmRunner.INVENTORY_CSV));
        assertFalse(inventory.contains("Doe"), "name-prefixed file names are hidden");
        assertTrue(inventory.contains("MyLinkedListTester.java"), "excluded tester is listed as excluded");
        assertTrue(inventory.contains("no code remaining after starter exclusion"));
        assertTrue(inventory.contains("no .java files"));
        assertTrue(Files.readString(idMap).contains("F25Hw1-S01,F25Hw1,1_111_aaa_1"));

        // Resume: nothing is re-sent.
        calls.set(0);
        new PdmRunner(config(subs, starter, ref, out, idMap, true), mock).run();
        assertEquals(0, calls.get());
        assertTrue(Files.readString(out.resolve(PdmRunner.COMPARISONS_CSV)).contains("PEER,LLM,F25Hw1-S01,F25Hw1-S02,F25Hw1,F25Hw1,WITHIN,100.00"));
    }

    @Test
    void keepModeDoesNotStripStarterOrExcludeFiles(@TempDir Path dir) throws IOException {
        Path subs = dir.resolve("F25Hw1");
        write(subs.resolve("1_a/MyLinkedList.java"), STARTER);
        write(subs.resolve("2_b/MyLinkedList.java"), STARTER);
        Path starter = write(dir.resolve("starter/MyLinkedList.java"), STARTER);
        PdmConfig keep = new PdmConfig("F25Hw1", List.of(new DataLayout.Cohort("F25Hw1", subs)), List.of(starter), Set.of("MyLinkedListTester.java"), List.of(),
                StarterMode.KEEP, 0.5, 5, dir.resolve("out"), null, 1, null, null, null, 100_000, 0,
                null, null, null, false, false);
        PdmRunner.Summary s = new PdmRunner(keep, null).run();
        assertEquals(2, s.eligibleSubmissions());
        assertTrue(Files.readString(dir.resolve("out").resolve(PdmRunner.COMPARISONS_CSV))
                .contains("PEER,TOKEN,F25Hw1-S01,F25Hw1-S02,F25Hw1,F25Hw1,WITHIN,100.00"), "unstripped starter code looks identical");
    }

    private static PdmConfig config(Path subs, Path starter, Path ref, Path out, Path idMap, boolean resume) {
        return new PdmConfig("F25Hw1", List.of(new DataLayout.Cohort("F25Hw1", subs)), List.of(starter), Set.of("MyLinkedListTester.java"), List.of(ref),
                StarterMode.STRIP, 0.5, 5, out, idMap, 2, null, 500, null, 100_000, 0,
                1.0, 2.0, "test", resume, false);
    }

    private static Path write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }
}
