package aiaa.labels;

import aiaa.pdm.PdmMain;
import aiaa.pdm.PdmRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-style root -> generator -> labeler, plus PDM pickup of controls. */
class LabelerTest {

    @TempDir
    static Path dir;
    static Path synth;
    static Path labels;
    static Path controls;
    static Labeler.Summary summary;
    static List<Map<String, String>> pairs;
    static List<Map<String, String>> submissions;

    @BeforeAll
    static void generateAndLabel() throws IOException {
        Fixture f = Fixture.build(dir, 2, 7);
        controls = f.controls;
        synth = f.synth;
        labels = f.labels;
        summary = f.summary;
        pairs = Labeler.readCsv(labels.resolve("Hw1/pair_labels.csv"));
        submissions = Labeler.readCsv(labels.resolve("Hw1/submission_labels.csv"));
    }

    private static Map<String, String> pair(String a, String b) {
        String key = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
        return pairs.stream().filter(p -> p.get("pair_key").equals(key)).findFirst().orElseThrow();
    }

    @Test
    void variantsAreCopiesOfTheirSourceAndHost() {
        int checked = 0;
        for (Map<String, String> s : submissions) {
            if (!s.get("kind").equals("VARIANT")) {
                continue;
            }
            Map<String, String> p = pair(s.get("submission"), s.get("source_id"));
            assertEquals("COPY", p.get("label"));
            assertEquals("SOURCE", p.get("relation"));
            assertEquals(s.get("transformation"), p.get("transformation"));
            if (!s.get("host_id").isEmpty()) {
                Map<String, String> h = pair(s.get("submission"), s.get("host_id"));
                assertEquals("HOST", h.get("relation"));
                double amount = Double.parseDouble(s.get("amount_copied"));
                assertEquals(1 - amount, Double.parseDouble(h.get("amount_copied")), 0.01);
            }
            checked++;
        }
        assertTrue(checked >= 12, "two sources x ~7 variants");
        // F25Hw1 has a non-source original, so its partial copy must use it as host (no source/host chaining)
        java.util.Set<String> sources = submissions.stream().filter(s -> s.get("kind").equals("VARIANT"))
                .map(s -> s.get("source_id")).collect(Collectors.toSet());
        submissions.stream().filter(s -> s.get("cohort").equals("F25Hw1") && !s.get("host_id").isEmpty())
                .forEach(s -> assertFalse(sources.contains(s.get("host_id")), "host is not a source: " + s));
        assertTrue(pairs.stream().anyMatch(p -> p.get("relation").equals("SIBLING") && p.get("label").equals("COPY")));
    }

    @Test
    void negativesUnresolvedAndExcludedFollowProvenance() {
        assertEquals("NEGATIVE", pair("Hw1-N01", "F25Hw1-S01").get("label"));
        assertEquals("NEGATIVE", pair("Hw1-N02", "F25Hw1-V01").get("label"), "controls are negative vs copies too");
        assertEquals("EXCLUDED", pair("Hw1-N01", "Hw1-N02").get("label"), "same-author controls");
        assertEquals("UNRESOLVED", pair("F25Hw1-S01", "Sp26Hw1-S02").get("label"), "originals are never negatives");
        assertTrue(submissions.stream().allMatch(s -> !s.get("kind").equals("ORIGINAL")
                || s.get("label").equals("UNRESOLVED")));
    }

    @Test
    void familiesNeverStraddleSplits() {
        assertTrue(pairs.stream().filter(p -> p.get("label").equals("COPY"))
                .noneMatch(p -> p.get("split").equals("CROSS_SPLIT")), "every copy pair is inside one split");
        Map<String, String> split = new HashMap<>();
        submissions.forEach(s -> split.put(s.get("submission"), s.get("split")));
        assertEquals(split.get("F25Hw1-S01"), split.get("Sp26Hw1-S01"), "same student (111) in both terms");
        Map<String, Long> controlSplits = submissions.stream().filter(s -> s.get("kind").equals("CONTROL"))
                .collect(Collectors.groupingBy(s -> s.get("split"), Collectors.counting()));
        assertEquals(Map.of("DEV", 2L, "VALIDATION", 1L, "TEST", 1L), controlSplits);
    }

    @Test
    void outputsContainNoStudentIdentifiers() throws IOException {
        for (Path f : List.of(labels.resolve("Hw1/pair_labels.csv"), labels.resolve("Hw1/submission_labels.csv"),
                labels.resolve("split_manifest.csv"), labels.resolve("split_log.json"))) {
            String text = Files.readString(f);
            assertFalse(text.contains("_111_") || text.contains("1_111"), f + " leaks a folder name");
        }
    }

    @Test
    void splitIsDeterministicForASeed() throws IOException {
        Path again = dir.resolve("labels-again");
        LabelMain.run(new String[]{"--data-root", synth.toString(), "--controls", controls.toString(),
                "--private-dir", dir.resolve("private").toString(), "--out", again.toString(), "--seed", "7"});
        assertEquals(Files.readString(labels.resolve("split_manifest.csv")),
                Files.readString(again.resolve("split_manifest.csv")));
        Path other = dir.resolve("labels-other");
        LabelMain.run(new String[]{"--data-root", synth.toString(), "--controls", controls.toString(),
                "--private-dir", dir.resolve("private").toString(), "--out", other.toString(), "--seed", "8"});
        assertNotEquals(Files.readString(labels.resolve("split_manifest.csv")),
                Files.readString(other.resolve("split_manifest.csv")));
    }

    @Test
    void pdmPicksUpControlsAndIdsJoinWithLabels() throws IOException {
        Path out = dir.resolve("pdm");
        PdmRunner.Summary s = PdmMain.run(new String[]{"--data-root", synth.toString(), "--homework", "Hw1",
                "--controls", controls.toString(), "--out", out.toString(),
                "--id-map", dir.resolve("private/pdm.csv").toString()});
        assertEquals(submissions.size(), s.eligibleSubmissions() + s.excludedSubmissions());
        List<Map<String, String>> comparisons = Labeler.readCsv(out.resolve("comparisons.csv"));
        Map<String, String> labelByKey = new HashMap<>();
        pairs.forEach(p -> labelByKey.put(p.get("pair_key"), p.get("label")));
        for (Map<String, String> c : comparisons) {
            String a = c.get("sub_a");
            String b = c.get("sub_b");
            String key = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
            assertTrue(labelByKey.containsKey(key), "every PDM pair has a label: " + key);
        }
        assertNull(labelByKey.get("Hw1-N01|Hw1-N01"));
    }
}
