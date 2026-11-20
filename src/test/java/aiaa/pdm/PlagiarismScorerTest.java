package aiaa.pdm;

import aiaa.pdm.ComparisonResult.Comparison;
import aiaa.pdm.ComparisonResult.Method;
import aiaa.pdm.ComparisonResult.Status;
import aiaa.pdm.PlagiarismScorer.StudentScore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlagiarismScorerTest {

    @Test
    void combinesMaxPeerAndMaxReferenceWithAlpha() {
        List<ComparisonResult> results = List.of(
                ok(Comparison.PEER, "S1", "S2", 40),
                ok(Comparison.PEER, "S1", "S3", 90),
                ok(Comparison.PEER, "S2", "S3", 10),
                failed(Comparison.PEER, "S1", "S4"),
                ok(Comparison.AI_REFERENCE, "S1", "REF-a", 20),
                ok(Comparison.AI_REFERENCE, "S1", "REF-b", 60));
        StudentScore s1 = PlagiarismScorer.score(List.of("S1"), results, Method.LLM, 0.7).getFirst();
        assertEquals(90.0, s1.maxPeer());
        assertEquals("S3", s1.maxPeerPartner());
        assertEquals(2, s1.peerOk());
        assertEquals(1, s1.peerFailed());
        assertEquals(60.0, s1.aiSimilarity());
        assertEquals("REF-b", s1.aiReference());
        assertEquals(0.7 * 90 + 0.3 * 60, s1.combined(), 1e-9);
    }

    @Test
    void combinedIsBlankWithoutReferencesAndMethodsAreNotMixed() {
        List<ComparisonResult> results = List.of(ok(Comparison.PEER, "S1", "S2", 50));
        StudentScore s2 = PlagiarismScorer.score(List.of("S2"), results, Method.LLM, 0.5).getFirst();
        assertEquals(50.0, s2.maxPeer());
        assertEquals("S1", s2.maxPeerPartner());
        assertNull(s2.combined());
        assertNull(PlagiarismScorer.score(List.of("S2"), results, Method.TOKEN, 0.5).getFirst().maxPeer());
    }

    @Test
    void alphaOutsideUnitIntervalIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PlagiarismScorer.combine(1, 1, 1.5));
    }

    private static ComparisonResult ok(Comparison c, String a, String b, double s) {
        return new ComparisonResult(c, Method.LLM, a, b, s, null, null, null, null, null, null, null,
                Status.OK, null, "mock", "m", "m", "v", 0, 0, 0, 0, null);
    }

    private static ComparisonResult failed(Comparison c, String a, String b) {
        return new ComparisonResult(c, Method.LLM, a, b, null, null, null, null, null, null, null, null,
                Status.MODEL_ERROR, "boom", "mock", "m", null, "v", 0, 0, 0, 0, null);
    }
}
