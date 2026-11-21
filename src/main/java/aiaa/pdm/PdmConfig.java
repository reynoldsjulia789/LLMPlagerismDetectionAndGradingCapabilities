package aiaa.pdm;

import aiaa.data.DataLayout;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Everything that determines one PDM run. All of it is written to run_manifest.json.
 *
 * @param assignment          run label: a homework (Hw1) or one cohort (F25Hw1). Only submissions of the same
 *                            homework are ever compared; with several cohorts, pairs across cohorts are included
 *                            and tagged CROSS
 * @param cohorts             cohorts to compare (label + folder whose subfolders are individual submissions)
 * @param starterPaths        instructor starter code (directories, zips or files)
 * @param excludedFiles       file names (after display renaming) dropped in STRIP mode, e.g. supplied testers
 * @param referencePaths      AI-generated reference solutions (each a directory, zip or file); may be empty
 * @param starterMode         STRIP (primary) or KEEP (comparison without exclusion)
 * @param alpha               weight of the peer component in S_i
 * @param tokenK              k-gram length of the token baseline
 * @param outDir              output folder for CSVs, manifest and raw responses
 * @param idMapFile           private file mapping anonymous IDs to folder names (keep out of the archive)
 * @param parallelism         concurrent LLM calls
 * @param maxLlmPeerPairs     cap on LLM peer pairs for smoke tests, or null for all pairs
 * @param maxOutputTokens     output token cap per call, or null
 * @param temperature         sampling temperature, or null to omit
 * @param maxInputChars       prompts longer than this are not sent (status TOO_LARGE) rather than truncated
 * @param maxRetries          retries per LLM call
 * @param priceInputPerMTok   USD per 1M input tokens, or null if unknown
 * @param priceOutputPerMTok  USD per 1M output tokens, or null if unknown
 * @param pricingSource       where the prices came from (URL + date), recorded in the manifest
 * @param resume              reuse successful responses already saved in outDir
 * @param idsFromFolders      always use folder names as submission IDs. Folders already named
 *                            {@code <cohort>-<ID>} (e.g. generated datasets) are used as IDs regardless
 */
public record PdmConfig(
        String assignment,
        List<DataLayout.Cohort> cohorts,
        List<Path> starterPaths,
        Set<String> excludedFiles,
        List<Path> referencePaths,
        StarterMode starterMode,
        double alpha,
        int tokenK,
        Path outDir,
        Path idMapFile,
        int parallelism,
        Integer maxLlmPeerPairs,
        Integer maxOutputTokens,
        Double temperature,
        int maxInputChars,
        int maxRetries,
        Double priceInputPerMTok,
        Double priceOutputPerMTok,
        String pricingSource,
        boolean resume,
        boolean idsFromFolders) {

    public PdmConfig {
        if (cohorts == null || cohorts.isEmpty()) {
            throw new IllegalArgumentException("at least one cohort is required");
        }
        if (alpha < 0 || alpha > 1) {
            throw new IllegalArgumentException("alpha must be in [0,1]");
        }
        if (tokenK < 1) {
            throw new IllegalArgumentException("tokenK must be >= 1");
        }
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be >= 1");
        }
    }

    public Double costUsd(long inputTokens, long outputTokens) {
        if (priceInputPerMTok == null || priceOutputPerMTok == null) {
            return null;
        }
        return (inputTokens * priceInputPerMTok + outputTokens * priceOutputPerMTok) / 1_000_000.0;
    }
}
