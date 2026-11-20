package aiaa.data;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Built-in facts about each homework: which instructor starter archive it uses and which
 * supplied files (testers) are excluded from similarity measurement. Command-line flags
 * always override these defaults.
 *
 * @param name          canonical name, e.g. {@code Hw1}
 * @param starterFile   file in {@code HomeworkDescriptions/}, or null if there is no starter code
 * @param excludedFiles supplied files that are not student work
 */
public record HomeworkSpec(String name, String starterFile, List<String> excludedFiles) {

    /** HW8's Tester is written by the student (separate LinkedList, Queue and Tester classes), so it is kept. */
    public static final List<HomeworkSpec> KNOWN = List.of(
            new HomeworkSpec("Hw1", "hw1_description_startupcode.zip", List.of("MyLinkedListTester.java")),
            new HomeworkSpec("Hw2", "hw2_description_startupcode.zip", List.of("Tester.java")),
            new HomeworkSpec("Hw8", null, List.of()));

    /** Case-insensitive lookup ("hw1", "HW1", "Hw1"). */
    public static Optional<HomeworkSpec> find(String name) {
        return KNOWN.stream().filter(h -> h.name().equalsIgnoreCase(name)).findFirst();
    }

    /** Spec for {@code name}, or an empty spec (no starter, nothing excluded) for unknown homework. */
    public static HomeworkSpec forName(String name) {
        return find(name).orElseGet(() -> new HomeworkSpec(canonical(name), null, List.of()));
    }

    static String canonical(String name) {
        String n = name.trim();
        return n.isEmpty() ? n : n.substring(0, 1).toUpperCase(Locale.ROOT) + n.substring(1).toLowerCase(Locale.ROOT);
    }
}
