package aiaa.eval;

import aiaa.labels.Fixture;
import aiaa.pdm.Csv;
import aiaa.pdm.PdmMain;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** generator -> labels -> PDM (both starter modes) -> select -> freeze -> test, with the freeze guards. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EvaluatorTest {

    @TempDir
    static Path dir;
    static Fixture f;
    static Path strip;
    static Path keep;
    static Path out;

    @BeforeAll
    static void setUp() throws IOException {
        f = Fixture.build(dir, 3, 11);
        strip = dir.resolve("pdm-strip");
        keep = dir.resolve("pdm-keep");
        for (Path p : List.of(strip, keep)) {
            PdmMain.run(new String[]{"--data-root", f.synth.toString(), "--homework", "Hw1", "--controls",
                    f.controls.toString(), "--out", p.toString(), "--id-map", dir.resolve("private/pdm.csv").toString(),
                    "--starter-mode", p == strip ? "strip" : "keep"});
        }
        out = dir.resolve("eval");
    }

    private static String[] args(String phase, String... extra) {
        List<String> a = new java.util.ArrayList<>(List.of(phase, "--labels", f.labels.toString(), "--run",
                strip.toString(), "--run", keep.toString(), "--out", out.toString(), "--bootstrap", "200"));
        a.addAll(List.of(extra));
        return a.toArray(String[]::new);
    }

    @Test
    @Order(1)
    void testBeforeSelectIsRefused() {
        assertThrows(IllegalStateException.class, () -> EvalMain.run(args("test")));
    }

    @Test
    @Order(2)
    void selectFreezesThresholdsPerConfiguration() throws IOException {
        @SuppressWarnings("unchecked")
        List<ThresholdSelector.Selection> sel = (List<ThresholdSelector.Selection>) EvalMain.run(args("select"));
        // token baseline, PEER, pair + student level, two starter modes
        assertEquals(4, sel.size());
        assertTrue(Files.exists(out.resolve("thresholds.json")));
        String frozen = Files.readString(out.resolve("thresholds.json"));
        assertTrue(frozen.contains("input_sha256") && frozen.contains("pair_labels.csv"));
        List<Map<String, String>> sweep = Csv.read(out.resolve("sweep.csv"));
        assertTrue(sweep.stream().anyMatch(r -> r.get("split").equals("DEV")));
        assertTrue(sweep.stream().noneMatch(r -> r.get("split").equals("TEST")), "selection never looks at TEST");
        assertThrows(IllegalStateException.class, () -> EvalMain.run(args("select")), "already frozen");
    }

    @Test
    @Order(3)
    void testAppliesFrozenThresholdsOnce() throws IOException {
        Object res = EvalMain.run(args("test"));
        assertFalse(((List<?>) res).isEmpty());
        for (String file : List.of("test_results.csv", "test_breakdown.csv", "test_errors.csv", "detection_results.csv",
                "sweep_test_postfreeze.csv", "starter_mode_comparison.csv", "test_evaluation.json")) {
            assertTrue(Files.exists(out.resolve(file)), file);
        }
        List<Map<String, String>> detections = Csv.read(out.resolve("detection_results.csv"));
        assertTrue(detections.stream().anyMatch(r -> r.get("outcome").equals("TP")));
        assertTrue(detections.stream().allMatch(r -> !r.get("threshold").isEmpty()));
        assertEquals(2, Csv.read(out.resolve("starter_mode_comparison.csv")).size(), "pair + student, STRIP vs KEEP");

        assertThrows(IllegalStateException.class, () -> EvalMain.run(args("test")), "only one held-out evaluation");
        assertThrows(IllegalStateException.class, () -> EvalMain.run(args("select", "--overwrite")),
                "no re-selection after seeing test results");
        EvalMain.run(args("test", "--allow-reevaluation"));
        assertTrue(Files.readString(out.resolve("evaluation_log.jsonl")).contains("test_reevaluation"));
    }

    @Test
    @Order(4)
    void changedInputsAfterFreezeAreRefused() throws IOException {
        Path out2 = dir.resolve("eval2");
        String[] select = {"select", "--labels", f.labels.toString(), "--run", strip.toString(), "--out", out2.toString()};
        EvalMain.run(select);
        Path labels = f.labels.resolve("Hw1/pair_labels.csv");
        Files.writeString(labels, Files.readString(labels) + "\r\n");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> EvalMain.run(new String[]{"test",
                "--labels", f.labels.toString(), "--run", strip.toString(), "--out", out2.toString()}));
        assertTrue(e.getMessage().contains("changed since freeze"));
    }
}
