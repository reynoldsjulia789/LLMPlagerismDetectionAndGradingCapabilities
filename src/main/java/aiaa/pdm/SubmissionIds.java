package aiaa.pdm;

import aiaa.data.DataLayout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The one rule for submission IDs, shared by the PDM and the labeler so their outputs join:
 * folders already named like an anonymous ID ({@code F25Hw1-S03}, {@code F25Hw1-V12},
 * {@code Hw1-N02}) keep their name; any other folder becomes {@code <cohort>-Sxx}, numbered
 * in {@link SourceFiles#submissionFolders} order.
 */
public final class SubmissionIds {

    private static final Pattern ANONYMOUS = Pattern.compile("[A-Za-z0-9]+-[A-Z]\\d+");

    public record Entry(String id, String cohort, Path folder) {
        public boolean renamed() {
            return !id.equals(folder.getFileName().toString());
        }
    }

    private SubmissionIds() {
    }

    public static boolean isAnonymous(String folderName) {
        return ANONYMOUS.matcher(folderName).matches();
    }

    /** @param forceFolderNames use folder names as IDs even if they do not look anonymous */
    public static List<Entry> assign(DataLayout.Cohort cohort, boolean forceFolderNames) {
        List<Entry> out = new ArrayList<>();
        List<Path> folders = SourceFiles.submissionFolders(cohort.dir());
        for (int i = 0; i < folders.size(); i++) {
            String name = folders.get(i).getFileName().toString();
            String id = forceFolderNames || isAnonymous(name) ? name : String.format("%s-S%02d", cohort.label(), i + 1);
            out.add(new Entry(id, cohort.label(), folders.get(i)));
        }
        return out;
    }
}
