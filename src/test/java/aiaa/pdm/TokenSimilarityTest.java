package aiaa.pdm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenSimilarityTest {

    private static final String ORIGINAL = """
            import java.util.NoSuchElementException;
            public class MyLinkedList {
                // remove the first node
                public Object removeFirst() throws Exception {
                    if (size == 0) {
                        throw new Exception("List is empty");
                    }
                    ListNode first = head.next;
                    head.next = first.next;
                    size--;
                    return first.data;
                }
            }
            """;

    /** Same code: identifiers renamed, reformatted, comments and messages changed, import changed. */
    private static final String DISGUISED = """
            import java.util.*;
            public class MyLinkedList
            {
                /* takes off the front element */
                public Object removeFirst() throws Exception
                {
                    if (count == 0) { throw new Exception("nothing to remove"); }
                    ListNode victim = dummy.next;   dummy.next = victim.next;
                    count--;
                    return victim.data;
                }
            }
            """;

    private static final String DIFFERENT = """
            public class MyLinkedList {
                public Object removeFirst() throws Exception {
                    try {
                        return remove(0);
                    } catch (IndexOutOfBoundsException e) {
                        throw new Exception(e);
                    }
                }
            }
            """;

    @Test
    void renamingReformattingAndCommentEditsDoNotChangeTheScore() {
        TokenSimilarity.Score s = TokenSimilarity.compare(ORIGINAL, DISGUISED);
        assertEquals(100.0, s.jaccard(), 1e-9);
        assertEquals(100.0, s.containment(), 1e-9);
    }

    @Test
    void differentImplementationScoresLower() {
        TokenSimilarity.Score s = TokenSimilarity.compare(ORIGINAL, DIFFERENT);
        assertTrue(s.jaccard() < 40, "jaccard was " + s.jaccard());
    }

    @Test
    void containmentDetectsPartialCopyBetterThanJaccard() {
        String bigger = ORIGINAL.replace("}\n}", """
                }
                public int indexOf(Object o) {
                    int i = 0;
                    for (ListNode n = head.next; n != null; n = n.next, i++) {
                        if (o == null ? n.data == null : o.equals(n.data)) { return i; }
                    }
                    return -1;
                }
            }""");
        TokenSimilarity.Score s = TokenSimilarity.compare(ORIGINAL, bigger);
        // Only the k-grams spanning the insertion point are lost.
        assertTrue(s.containment() > 95, "containment was " + s.containment());
        assertTrue(s.jaccard() < 70, "jaccard was " + s.jaccard());
    }

    @Test
    void tokenizerNormalizesIdentifiersLiteralsAndDropsImports() {
        List<String> t = TokenSimilarity.tokenize("import a.b.C; int x = 42; String s = \"hi\"; x >>>= 'c'; // c");
        assertEquals(List.of("int", "ID", "=", "NUM", ";", "ID", "ID", "=", "STR", ";", "ID", ">>>=", "CHR", ";"), t);
    }

    @Test
    void emptyInputScoresZero() {
        assertEquals(0.0, TokenSimilarity.compare("", ORIGINAL).jaccard());
    }
}
