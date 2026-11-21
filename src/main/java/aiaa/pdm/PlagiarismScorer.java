package aiaa.pdm;

import aiaa.pdm.ComparisonResult.Comparison;
import aiaa.pdm.ComparisonResult.Method;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Student-level plagiarism scores, computed separately for each method (LLM, token baseline):
 *
 * <pre>  S_i = alpha * max_j(S_ij) + (1 - alpha) * S_i_AI</pre>
 *
 * where S_i_AI is the maximum similarity to any reference in the reference bank. The peer and
 * AI components are always reported separately as well, because thresholds are selected for
 * each of them independently; S_i is a combined screening score. Failed comparisons are
 * excluded from the maxima and counted so incomplete scores are visible.
 *
 * <p>When several cohorts are pooled, the peer maximum is also reported separately over
 * same-cohort and cross-cohort partners. S_i uses the maximum over all partners.
 */
public final class PlagiarismScorer {

    private PlagiarismScorer() {
    }

    public record StudentScore(
            String submission,
            Method method,
            Double maxPeer,
            String maxPeerPartner,
            Double maxPeerWithin,
            Double maxPeerCross,
            int peerOk,
            int peerFailed,
            Double aiSimilarity,
            String aiReference,
            int aiOk,
            int aiFailed,
            double alpha,
            Double combined) {
    }

    public static double combine(double maxPeer, double aiSimilarity, double alpha) {
        if (alpha < 0 || alpha > 1) {
            throw new IllegalArgumentException("alpha must be in [0,1]: " + alpha);
        }
        return alpha * maxPeer + (1 - alpha) * aiSimilarity;
    }

    /** Single-cohort convenience: every pair counts as within-cohort. */
    public static List<StudentScore> score(List<String> submissions, List<ComparisonResult> results,
                                           Method method, double alpha) {
        return score(submissions, results, method, alpha, Map.of());
    }

    /** @param cohortOf submission ID -> cohort label; missing entries count as one shared cohort */
    public static List<StudentScore> score(List<String> submissions, List<ComparisonResult> results,
                                           Method method, double alpha, Map<String, String> cohortOf) {
        List<StudentScore> out = new ArrayList<>();
        for (String sub : submissions) {
            Double maxPeer = null;
            String partner = null;
            Double within = null;
            Double cross = null;
            int peerOk = 0;
            int peerFailed = 0;
            Double ai = null;
            String ref = null;
            int aiOk = 0;
            int aiFailed = 0;
            for (ComparisonResult r : results) {
                if (r.method() != method) {
                    continue;
                }
                if (r.comparison() == Comparison.PEER && (r.subA().equals(sub) || r.subB().equals(sub))) {
                    if (!r.ok()) {
                        peerFailed++;
                        continue;
                    }
                    peerOk++;
                    String other = r.subA().equals(sub) ? r.subB() : r.subA();
                    if (maxPeer == null || r.similarity() > maxPeer) {
                        maxPeer = r.similarity();
                        partner = other;
                    }
                    boolean sameCohort = java.util.Objects.equals(cohortOf.get(sub), cohortOf.get(other));
                    if (sameCohort) {
                        within = within == null ? r.similarity() : Math.max(within, r.similarity());
                    } else {
                        cross = cross == null ? r.similarity() : Math.max(cross, r.similarity());
                    }
                } else if (r.comparison() == Comparison.AI_REFERENCE && r.subA().equals(sub)) {
                    if (!r.ok()) {
                        aiFailed++;
                        continue;
                    }
                    aiOk++;
                    if (ai == null || r.similarity() > ai) {
                        ai = r.similarity();
                        ref = r.subB();
                    }
                }
            }
            Double combined = (maxPeer != null && ai != null) ? combine(maxPeer, ai, alpha) : null;
            out.add(new StudentScore(sub, method, maxPeer, partner, within, cross, peerOk, peerFailed, ai, ref, aiOk, aiFailed,
                    alpha, combined));
        }
        return out;
    }
}
