package aiaa.eval;

/**
 * One labeled score: a pair (pair level) or a submission (student level).
 *
 * @param id             pair key (a|b) or submission ID
 * @param subA           first submission (pair level) or the submission (student level)
 * @param subB           second side / reference (pair level), or best-matching partner (student level)
 * @param split          DEV / VALIDATION / TEST
 * @param truth          POSITIVE, NEGATIVE or UNRESOLVED
 * @param score          similarity 0-100, or null when the comparison failed (counts as not flagged)
 * @param cluster        independent unit for the bootstrap: the family for positives, the control for negatives
 * @param transformation transformation of the copy (positives)
 * @param relation       SOURCE / HOST / SIBLING (positives), CONTROL (negatives)
 * @param amountCopied   fraction copied (positives), or null
 * @param cohortRelation WITHIN / CROSS / REFERENCE (pair level)
 * @param evidence       evidence locations from the model, if any
 * @param status         comparison status (OK, MODEL_ERROR, ...)
 */
public record Observation(String id, String subA, String subB, String split, Truth truth, Double score,
                          String cluster, String transformation, String relation, Double amountCopied,
                          String cohortRelation, String evidence, String status) {

    public enum Truth { POSITIVE, NEGATIVE, UNRESOLVED }

    public boolean flagged(double threshold) {
        return score != null && score >= threshold;
    }
}
