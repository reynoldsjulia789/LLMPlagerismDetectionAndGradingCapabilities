package aiaa.pdm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StarterCodeFilterTest {

    private static final String STARTER = """
            public class MyLinkedList {
                // Please DO NOT change this addFirst() method.
                public void addFirst(Object e) {
                    size++;
                }
                public boolean contains(Object o) {
                    return false; //change this as you need.
                }
            }
            """;

    @Test
    void removesStarterLinesButKeepsStudentCodeAndBraces() {
        String student = """
                public class MyLinkedList {
                    // Please DO NOT change this addFirst() method.
                    public void addFirst(Object e) {
                        size++;
                    }
                    public boolean contains(Object o) {
                        for (ListNode n = head.next; n != null; n = n.next) {
                            if (o == null ? n.data == null : o.equals(n.data)) return true;
                        }
                        return false;
                    }
                }
                """;
        StarterCodeFilter f = StarterCodeFilter.fromSources(List.of(STARTER));
        StarterCodeFilter.Result r = f.filter(SourceView.of("S1", Map.of("MyLinkedList.java", student)));
        String kept = r.view().plainText();

        assertFalse(kept.contains("addFirst"), "starter method signature removed");
        assertFalse(kept.contains("DO NOT change"), "starter comment removed");
        assertTrue(kept.contains("o.equals(n.data)"), "student logic kept");
        // "return false;" differs from the starter line (which has a trailing comment), so it is kept.
        assertTrue(kept.contains("return false;"));
        assertTrue(kept.contains("}"), "braces are never removed");
        assertEquals(5, r.removedLines());
    }

    @Test
    void multisetMatchingRemovesEachStarterLineOnlyAsOftenAsItOccurs() {
        StarterCodeFilter f = StarterCodeFilter.fromSources(List.of("size++;\n"));
        String student = "size++;\nsize++;\nsize++;\n";
        StarterCodeFilter.Result r = f.filter(SourceView.of("S1", Map.of("A.java", student)));
        assertEquals(1, r.removedLines());
        assertEquals(2, r.view().substantiveLineCount());
    }

    @Test
    void whitespaceDifferencesStillMatchAndOriginalLineNumbersArePreserved() {
        StarterCodeFilter f = StarterCodeFilter.fromSources(List.of("int   x = 0;"));
        String student = "int y = 1;\n\t int x =  0;\nint z = 2;";
        StarterCodeFilter.Result r = f.filter(SourceView.of("S1", Map.of("A.java", student)));
        assertEquals(1, r.removedLines());
        List<SourceView.Line> lines = r.view().files().getFirst().lines();
        assertEquals(1, lines.get(0).number());
        assertEquals(3, lines.get(1).number(), "line 3 keeps its original number after line 2 is removed");
        assertTrue(r.view().numberedText().contains("   3| int z = 2;"));
    }
}
