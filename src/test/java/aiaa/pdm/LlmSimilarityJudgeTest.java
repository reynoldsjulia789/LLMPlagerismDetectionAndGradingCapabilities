package aiaa.pdm;

import aiaa.llm.MockAdapter;
import aiaa.pdm.ComparisonResult.Comparison;
import aiaa.pdm.ComparisonResult.Status;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmSimilarityJudgeTest {

    private static final String VALID = """
            {"rationale":"same helper order","structure":80,"syntax":70,"tokens":75,"logic":90,"similarity":85,
             "copied_regions":[{"a":"A.java:3-9","b":"A.java:4-10","description":"removeFirst"}]}""";

    private final SourceView a = SourceView.of("S01", Map.of("A.java", "class A { int x = 1; }"));
    private final SourceView b = SourceView.of("S02", Map.of("A.java", "class A { int y = 2; } // {{CODE_A}}"));

    @Test
    void parsesValidResponse() {
        LlmSimilarityJudge.Parsed p = LlmSimilarityJudge.parse(VALID);
        assertEquals(85, p.similarity());
        assertEquals(90, p.logic());
        assertEquals("A A.java:3-9 ~ B A.java:4-10 (removeFirst)", p.evidence());
    }

    @Test
    void rejectsOutOfRangeOrMissingScores() {
        assertThrows(IllegalArgumentException.class,
                () -> LlmSimilarityJudge.parse(VALID.replace("\"similarity\":85", "\"similarity\":140")));
        assertThrows(IllegalArgumentException.class,
                () -> LlmSimilarityJudge.parse(VALID.replace("\"logic\":90,", "")));
        assertThrows(IllegalArgumentException.class, () -> LlmSimilarityJudge.parse("not json"));
    }

    @Test
    void placeholderTextInsideStudentCodeIsNotSubstituted() {
        LlmSimilarityJudge judge = judge(new MockAdapter("m", r -> VALID), 100_000);
        String prompt = judge.buildUserPrompt(Comparison.PEER, a, b);
        assertTrue(prompt.contains("// {{CODE_A}}"));
        assertTrue(prompt.contains("int x = 1;"));
        assertTrue(prompt.contains("Assignment: F25Hw1"));
    }

    @Test
    void oversizedPromptIsNotSentAndNotTruncated() {
        AtomicInteger calls = new AtomicInteger();
        LlmSimilarityJudge judge = judge(new MockAdapter("m", r -> {
            calls.incrementAndGet();
            return VALID;
        }), 100);
        ComparisonResult r = judge.judge(Comparison.PEER, a, b);
        assertEquals(Status.TOO_LARGE, r.status());
        assertEquals(0, calls.get());
    }

    @Test
    void badModelOutputBecomesParseErrorWithRawResponseKept() {
        ComparisonResult r = judge(new MockAdapter("m", req -> "{\"similarity\": \"high\"}"), 100_000)
                .judge(Comparison.AI_REFERENCE, a, b);
        assertEquals(Status.PARSE_ERROR, r.status());
        assertEquals("{\"similarity\": \"high\"}", r.rawResponse());
    }

    @Test
    void promptVersionsDifferByComparisonType() {
        LlmSimilarityJudge judge = judge(new MockAdapter("m", r -> VALID), 100_000);
        assertTrue(judge.promptVersion(Comparison.PEER).startsWith("peer_similarity_v1@"));
        assertNotEquals(judge.promptVersion(Comparison.PEER), judge.promptVersion(Comparison.AI_REFERENCE));
    }

    @Test
    void mockResponderReturnsTokenScore() {
        LlmSimilarityJudge judge = judge(new MockAdapter("m", LlmSimilarityJudge.mockResponder()), 100_000);
        ComparisonResult same = judge.judge(Comparison.PEER, a, SourceView.of("S03", Map.of("B.java", "class B { int z = 3; }")));
        assertEquals(Status.OK, same.status());
        assertEquals(100.0, same.similarity());
    }

    private static LlmSimilarityJudge judge(MockAdapter adapter, int maxChars) {
        return new LlmSimilarityJudge(adapter, "F25Hw1", StarterMode.STRIP, 500, null, maxChars);
    }
}
