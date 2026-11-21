package aiaa.data;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The on-disk dataset layout shared by every module:
 *
 * <pre>
 * &lt;root&gt;/
 *   HomeworkDescriptions/           starter archives, assignment descriptions
 *   HomeworkSubmissions/
 *     Hw1/
 *       F25Hw1/                     one cohort (term) of one homework
 *         &lt;submission folder&gt;/     one student's files
 *       Sp26Hw1/
 *     Hw2/ ...
 * </pre>
 *
 * The real data lives under {@code HomeworkSubmissions-test data/}; generated synthetic
 * datasets use the same layout under {@code synthetic-data/}, so either can be a root.
 */
public final class DataLayout {

    public static final String DEFAULT_ROOT = "HomeworkSubmissions-test data";
    public static final String SUBMISSIONS = "HomeworkSubmissions";
    public static final String DESCRIPTIONS = "HomeworkDescriptions";
    /** Committed folder of negative controls: {@code negative-controls/<Hw>/<Hw>-Nxx/}. */
    public static final String DEFAULT_CONTROLS_ROOT = "negative-controls";
    /** Cohort label used for negative controls when they are pooled with real cohorts. */
    public static final String CONTROLS_COHORT = "Controls";

    /** The homework's negative controls as a cohort, if {@code controlsRoot/<Hw>/} exists. */
    public static Optional<Cohort> controls(Path controlsRoot, String homework) {
        if (controlsRoot == null) {
            return Optional.empty();
        }
        Path dir = controlsRoot.resolve(HomeworkSpec.forName(homework).name());
        return Files.isDirectory(dir) ? Optional.of(new Cohort(CONTROLS_COHORT, dir)) : Optional.empty();
    }

    private DataLayout() {
    }

    /** One cohort of one homework: label (folder name, e.g. F25Hw1) and folder of submissions. */
    public record Cohort(String label, Path dir) {
    }

    /** Everything a module needs to process one homework (one or more cohorts). */
    public record Selection(HomeworkSpec homework, List<Cohort> cohorts, List<Path> starterPaths,
                            Set<String> excludedFiles) {
        /** Run label: the cohort label for a single cohort, else the homework name. */
        public String label() {
            return cohorts.size() == 1 ? cohorts.getFirst().label() : homework.name();
        }
    }

    public static Path homeworkDir(Path root, String homework) {
        return root.resolve(SUBMISSIONS).resolve(HomeworkSpec.forName(homework).name());
    }

    /**
     * Resolves {@code homework} (e.g. Hw1) and an optional {@code cohort} (F25 or F25Hw1; null = all
     * cohorts) under {@code root}, with that homework's default starter archive and excluded files.
     */
    public static Selection resolve(Path root, String homework, String cohort) {
        HomeworkSpec spec = HomeworkSpec.forName(homework);
        Path hwDir = homeworkDir(root, spec.name());
        if (!Files.isDirectory(hwDir)) {
            throw new IllegalArgumentException("No homework folder " + hwDir);
        }
        List<Cohort> cohorts = new ArrayList<>();
        for (Path dir : subdirectories(hwDir)) {
            String label = dir.getFileName().toString();
            if (cohort == null || label.equalsIgnoreCase(cohort) || label.equalsIgnoreCase(cohort + spec.name())) {
                cohorts.add(new Cohort(label, dir));
            }
        }
        if (cohorts.isEmpty()) {
            throw new IllegalArgumentException("No cohort " + (cohort == null ? "" : "'" + cohort + "' ")
                    + "under " + hwDir + "; found " + subdirectories(hwDir).stream().map(p -> p.getFileName().toString()).toList());
        }
        List<Path> starters = starterPath(root, spec).map(List::of).orElse(List.of());
        return new Selection(spec, cohorts, starters, new LinkedHashSet<>(spec.excludedFiles()));
    }

    /** The homework's starter archive under {@code root/HomeworkDescriptions}, if it has one and it exists. */
    public static Optional<Path> starterPath(Path root, HomeworkSpec spec) {
        if (spec.starterFile() == null) {
            return Optional.empty();
        }
        Path p = root.resolve(DESCRIPTIONS).resolve(spec.starterFile());
        if (!Files.exists(p)) {
            throw new IllegalArgumentException("Starter code for " + spec.name() + " not found at " + p
                    + " (pass --starter to override)");
        }
        return Optional.of(p);
    }

    /** Non-hidden subdirectories, sorted by name. */
    public static List<Path> subdirectories(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + dir, e);
        }
    }
}
