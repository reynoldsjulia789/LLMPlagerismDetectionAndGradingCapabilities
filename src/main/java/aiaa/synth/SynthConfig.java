package aiaa.synth;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Configuration of one synthetic-copy generation run (recorded in generation_log.json).
 *
 * @param assignment      label such as F25Hw1
 * @param submissionsDir  original submissions, one subfolder each
 * @param starterPaths    instructor starter code (used to measure amount copied on student-written lines)
 * @param excludedFiles   supplied files (e.g. testers) that are not counted / not rewritten
 * @param referencePaths  AI reference solutions to derive "AI copying" variants from (may be empty)
 * @param sourceCount     number of student submissions to derive variants from
 * @param seed            master random seed; every variant's seed is derived from it
 * @param outDir          dataset output folder (originals + variants + manifest)
 * @param idMapFile       private anonymous-ID mapping (keep out of any archive)
 * @param maxOutputTokens output cap for the control-flow LLM call
 * @param temperature     sampling temperature for the control-flow LLM call, or null to omit
 * @param maxLlmAttempts  attempts per control-flow variant (compile errors are fed back)
 */
public record SynthConfig(
        String assignment,
        Path submissionsDir,
        List<Path> starterPaths,
        Set<String> excludedFiles,
        List<Path> referencePaths,
        int sourceCount,
        long seed,
        Path outDir,
        Path idMapFile,
        Integer maxOutputTokens,
        Double temperature,
        int maxLlmAttempts) {
}
