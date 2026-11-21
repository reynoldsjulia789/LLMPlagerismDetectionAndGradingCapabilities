package aiaa.data;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Built-in facts about each homework: which instructor starter archive it uses, which supplied files
 * (testers) are excluded from similarity measurement, where its description lives and which class the
 * program is run with. Command-line flags always override these defaults.
 *
 * @param name          canonical name, e.g. {@code Hw1}
 * @param starterFile   file in {@code HomeworkDescriptions/}, or null if there is no starter code
 * @param excludedFiles supplied files that are not student work
 * @param description   description document in {@code HomeworkDescriptions/}; {@code archive.zip!entry} for a
 *                      document inside an archive; null if unknown
 * @param mainClass     class run by the grader ({@code javac *.java; java <mainClass>}), or null if unknown
 */
public record HomeworkSpec(String name, String starterFile, List<String> excludedFiles, String description,
                           String mainClass) {

    /** HW8's Tester is written by the student (separate LinkedList, Queue and Tester classes), so it is kept. */
    public static final List<HomeworkSpec> KNOWN = List.of(
            new HomeworkSpec("Hw1", "hw1_description_startupcode.zip", List.of("MyLinkedListTester.java"),
                    "hw1_description_startupcode.zip!hw1_25/hw1_warmup.docx", "MyLinkedListTester"),
            new HomeworkSpec("Hw2", "hw2_description_startupcode.zip", List.of("Tester.java"),
                    "hw2_description_startupcode.zip!hw2_19/hw2.docx", "Tester"),
            new HomeworkSpec("Hw8", null, List.of(), "hw8_description.docx", "Tester"));

    /** Case-insensitive lookup ("hw1", "HW1", "Hw1"). */
    public static Optional<HomeworkSpec> find(String name) {
        return KNOWN.stream().filter(h -> h.name().equalsIgnoreCase(name)).findFirst();
    }

    /** Spec for {@code name}, or an empty spec (no starter, nothing excluded) for unknown homework. */
    public static HomeworkSpec forName(String name) {
        return find(name).orElseGet(() -> new HomeworkSpec(canonical(name), null, List.of(), null, null));
    }

    static String canonical(String name) {
        String n = name.trim();
        return n.isEmpty() ? n : n.substring(0, 1).toUpperCase(Locale.ROOT) + n.substring(1).toLowerCase(Locale.ROOT);
    }
}
