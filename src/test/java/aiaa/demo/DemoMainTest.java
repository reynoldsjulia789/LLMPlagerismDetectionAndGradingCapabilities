package aiaa.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps the documented one-command demonstration working on the committed demo dataset. */
class DemoMainTest {

    @Test
    void oneCommandRegeneratesAllDemoOutputs(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("demo-output");
        DemoMain.run(new String[]{"--out", out.toString(), "--mock-llm"});
        for (String f : new String[]{"labels/split_log.json", "pdm/strip/comparisons.csv", "pdm/keep/comparisons.csv",
                "eval/thresholds.json", "eval/test_results.csv", "eval/detection_results.csv", DemoMain.MARKER}) {
            assertTrue(Files.exists(out.resolve(f)), f);
        }
        assertTrue(Files.readString(out.resolve("pdm/strip/comparisons.csv")).contains(",LLM,"), "mock LLM path ran");
        // rerunning replaces its own output folder
        DemoMain.run(new String[]{"--out", out.toString()});
        assertTrue(Files.exists(out.resolve("eval/test_results.csv")));
        // but never a folder it did not create
        Path foreign = Files.createDirectories(dir.resolve("other"));
        assertThrows(IllegalArgumentException.class, () -> DemoMain.run(new String[]{"--out", foreign.toString()}));
    }
}
