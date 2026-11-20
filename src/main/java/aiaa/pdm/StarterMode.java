package aiaa.pdm;

/** Whether instructor-provided material is removed before similarity scoring. */
public enum StarterMode {
    /** Remove starter-code lines and excluded files (e.g. supplied testers). Primary measurement. */
    STRIP,
    /** Compare full submissions as submitted. Used for the with/without-exclusion comparison. */
    KEEP
}
