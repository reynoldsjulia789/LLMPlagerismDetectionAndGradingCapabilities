package aiaa.labels;

import java.nio.file.Path;
import java.util.List;

/**
 * Configuration of one labeling + split run (recorded in split_log.json).
 *
 * @param dataRoot     dataset root in the shared layout (usually a generated synthetic-data root)
 * @param controlsRoot negative controls root ({@code negative-controls}), or null for none
 * @param homeworks    homework names to label; empty = every homework found under the root
 * @param privateDir   folder with the private ID maps, used only to keep a student's submissions in one
 *                     split; student IDs are never written to the outputs
 * @param outDir       where label and split files are written
 * @param seed         random seed for the split
 * @param fractions    target dev / validation / test fractions (sum 1)
 * @param groupStudentsAcrossHomeworks keep a student's submissions to different homeworks in one split
 *                     (otherwise only across terms of the same homework)
 */
public record LabelConfig(Path dataRoot, Path controlsRoot, List<String> homeworks, Path privateDir, Path outDir,
                          long seed, double[] fractions, boolean groupStudentsAcrossHomeworks) {

    public LabelConfig {
        if (fractions.length != 3 || Math.abs(fractions[0] + fractions[1] + fractions[2] - 1) > 1e-9
                || fractions[0] <= 0 || fractions[1] <= 0 || fractions[2] <= 0) {
            throw new IllegalArgumentException("split fractions must be three positive numbers summing to 1");
        }
    }
}
