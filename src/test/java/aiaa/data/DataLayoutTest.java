package aiaa.data;

import aiaa.pdm.PdmMain;
import aiaa.pdm.PdmRunner;
import aiaa.synth.SynthTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared dataset layout, end to end: real-style root -> PDM, generator -> synthetic root -> PDM. */
class DataLayoutTest {

    private static final String STARTER = """
            public class MyLinkedList<T> implements Iterable<T> {
                // Please DO NOT change this addFirst() method.
            }
            """;

    /** root/HomeworkSubmissions/Hw1/{F25Hw1,Sp26Hw1}/..., root/HomeworkDescriptions/hw1_...zip */
    private static Path buildRoot(Path dir) throws IOException {
        Path hw1 = dir.resolve("HomeworkSubmissions/Hw1");
        write(hw1.resolve("F25Hw1/1_111_a_1/MyLinkedList.java"), SynthTestSupport.list());
        write(hw1.resolve("F25Hw1/1_111_a_1/MyLinkedListTester.java"), SynthTestSupport.tester());
        write(hw1.resolve("F25Hw1/2_222_b_2/MyLinkedList.java"), SynthTestSupport.other());
        // Spring student copied the Fall student's file verbatim.
        write(hw1.resolve("Sp26Hw1/1_333_c_1/MyLinkedList.java"), SynthTestSupport.list());
        write(hw1.resolve("Sp26Hw1/2_444_d_2/MyLinkedList.java"), SynthTestSupport.other().replace("pos++", "pos += 1"));
        Files.createDirectories(hw1.resolve("F25Hw1/.hidden"));
        Path zip = dir.resolve("HomeworkDescriptions/hw1_description_startupcode.zip");
        Files.createDirectories(zip.getParent());
        try (OutputStream os = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(os)) {
            z.putNextEntry(new ZipEntry("hw1_25/startupCode/MyLinkedList.java"));
            z.write(STARTER.getBytes());
            z.closeEntry();
        }
        return dir;
    }

    @Test
    void resolvesHomeworkCohortsAndDefaults(@TempDir Path dir) throws IOException {
        Path root = buildRoot(dir);
        DataLayout.Selection all = DataLayout.resolve(root, "hw1", null);
        assertEquals("Hw1", all.label());
        assertEquals(List.of("F25Hw1", "Sp26Hw1"), all.cohorts().stream().map(DataLayout.Cohort::label).toList());
        assertEquals(1, all.starterPaths().size());
        assertTrue(all.excludedFiles().contains("MyLinkedListTester.java"));

        DataLayout.Selection one = DataLayout.resolve(root, "Hw1", "Sp26");
        assertEquals("Sp26Hw1", one.label());
        assertEquals("Sp26Hw1", DataLayout.resolve(root, "Hw1", "sp26hw1").label());
        assertThrows(IllegalArgumentException.class, () -> DataLayout.resolve(root, "Hw1", "W24"));
        assertThrows(IllegalArgumentException.class, () -> DataLayout.resolve(root, "Hw2", null));
        assertTrue(HomeworkSpec.forName("hw8").excludedFiles().isEmpty(), "HW8 Tester is student-written");
    }

    @Test
    void pooledCohortsAreComparedAndPairsTagged(@TempDir Path dir) throws IOException {
        Path root = buildRoot(dir);
        Path out = dir.resolve("out");
        PdmRunner.Summary s = PdmMain.run(new String[]{"--data-root", root.toString(), "--homework", "Hw1", "--no-controls",
                "--out", out.toString(), "--id-map", dir.resolve("private/ids.csv").toString()});
        assertEquals(4, s.eligibleSubmissions());
        assertEquals(6, s.peerPairs(), "4 submissions across 2 cohorts");

        String comparisons = Files.readString(out.resolve("comparisons.csv"));
        assertTrue(comparisons.contains("F25Hw1-S01,Sp26Hw1-S01,F25Hw1,Sp26Hw1,CROSS,100.00"),
                "cross-term verbatim copy found and tagged");
        assertTrue(comparisons.contains("F25Hw1-S01,F25Hw1-S02,F25Hw1,F25Hw1,WITHIN,"));
        assertTrue(Files.readString(out.resolve("submission_scores.csv")).contains("max_peer_cross_cohort"));
        String ids = Files.readString(dir.resolve("private/ids.csv"));
        assertTrue(ids.contains("Sp26Hw1-S01,Sp26Hw1,1_333_c_1"));
        assertFalse(comparisons.contains("333_c"), "folder names stay private");
    }

    @Test
    void generatedRootMirrorsLayoutAndCanBeScreened(@TempDir Path dir) throws IOException {
        Path root = buildRoot(dir);
        Path synth = dir.resolve("synthetic-data");
        SynthTestSupport.runGenerator(new String[]{"--data-root", root.toString(), "--homework", "Hw1",
                "--out-root", synth.toString(), "--private-dir", dir.resolve("private").toString(),
                "--sources", "1"});
        assertTrue(Files.isDirectory(synth.resolve("HomeworkSubmissions/Hw1/F25Hw1/F25Hw1-V01")));
        assertTrue(Files.isDirectory(synth.resolve("HomeworkSubmissions/Hw1/Sp26Hw1/Sp26Hw1-S02")));
        assertTrue(Files.exists(synth.resolve("HomeworkSubmissions/Hw1/F25Hw1/manifest.csv")));
        assertTrue(Files.exists(synth.resolve("HomeworkDescriptions/hw1_description_startupcode.zip")));
        assertTrue(Files.exists(dir.resolve("private/synth_id_map_Sp26Hw1.csv")));

        Path out = dir.resolve("out-synth");
        Path idMap = dir.resolve("private/pdm_ids.csv");
        PdmMain.run(new String[]{"--data-root", synth.toString(), "--homework", "Hw1", "--no-controls",
                "--out", out.toString(), "--id-map", idMap.toString()});
        String comparisons = Files.readString(out.resolve("comparisons.csv"));
        assertTrue(comparisons.contains("F25Hw1-V01"), "generated folder names are used as IDs");
        assertFalse(Files.exists(idMap), "already-anonymous dataset: no ID map needed");
    }

    private static void write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }
}
