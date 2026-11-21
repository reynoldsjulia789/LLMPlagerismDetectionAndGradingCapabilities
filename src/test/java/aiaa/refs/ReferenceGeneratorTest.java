package aiaa.refs;

import aiaa.data.AssignmentMaterials;
import aiaa.llm.MockAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A toy "Hw1" (description docx with CORRECT OUTPUT, starter, supplied tester) and a toy "Hw8". */
class ReferenceGeneratorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String TESTER = """
            public class MyLinkedListTester {
                public static void main(String[] args) {
                    MyLinkedList l = new MyLinkedList();
                    l.add("A");
                    l.add("B");
                    System.out.println(l);
                    System.out.println(l.size());
                }
            }
            """;
    private static final String STARTER = """
            public class MyLinkedList {
                private java.util.List<Object> items = new java.util.ArrayList<>();
                public boolean add(Object e) {
                    return true; //change this as you need.
                }
                public int size() { return items.size(); }
                @Override
                public String toString() { return items.toString(); }
            }
            """;
    private static final String GOOD = STARTER.replace("return true; //change this as you need.", "items.add(e);\n        return true;");
    private static final String WRONG = STARTER.replace("return true; //change this as you need.", "items.add(0, e);\n        return true;");

    @TempDir
    static Path root;
    static AssignmentMaterials hw1;

    @BeforeAll
    static void materials() throws IOException {
        Path desc = Files.createDirectories(root.resolve("HomeworkDescriptions"));
        try (OutputStream os = Files.newOutputStream(desc.resolve("hw1_description_startupcode.zip"));
             ZipOutputStream z = new ZipOutputStream(os)) {
            z.putNextEntry(new ZipEntry("hw1_25/hw1_warmup.docx"));
            z.write(docx("Toy homework", "Implement add().", "CORRECT OUTPUT of the program is provided below.",
                    "[A, B]", "", "2"));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("hw1_25/startupCode/MyLinkedList.java"));
            z.write(STARTER.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("hw1_25/startupCode/MyLinkedListTester.java"));
            z.write(TESTER.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        Files.write(desc.resolve("hw8_description.docx"), docx("HW8", "MergeSort and InsertionSort."));
        hw1 = AssignmentMaterials.load(root, "Hw1");
    }

    @Test
    void materialsSplitStarterTesterAndExpectedOutput() {
        assertEquals(List.of("MyLinkedList.java"), List.copyOf(hw1.starterFiles().keySet()));
        assertEquals(List.of("MyLinkedListTester.java"), List.copyOf(hw1.testerFiles().keySet()));
        assertEquals(List.of("[A, B]", "2"), hw1.expectedOutput());
        assertTrue(hw1.description().contains("Implement add()."));
    }

    @Test
    void verifierAcceptsCorrectAndExplainsFailures() {
        Duration t = Duration.ofSeconds(30);
        assertTrue(SolutionVerifier.verify(hw1, Map.of("MyLinkedList.java", GOOD), t).verified());

        SolutionVerifier.Result wrong = SolutionVerifier.verify(hw1, Map.of("MyLinkedList.java", WRONG), t);
        assertFalse(wrong.verified());
        assertTrue(wrong.detail().contains("line 1") && wrong.detail().contains("[B, A]"), wrong.detail());

        SolutionVerifier.Result broken = SolutionVerifier.verify(hw1, Map.of("MyLinkedList.java", "class X {"), t);
        assertFalse(broken.compiled());

        // a solution that tampers with the tester is ignored: the instructor's tester always wins
        SolutionVerifier.Result tamper = SolutionVerifier.verify(hw1, Map.of("MyLinkedList.java", WRONG,
                "MyLinkedListTester.java", "public class MyLinkedListTester { public static void main(String[] a) {"
                        + " System.out.println(\"[A, B]\"); System.out.println(2); } }"), t);
        assertFalse(tamper.verified());

        String loop = GOOD.replace("items.add(e);", "while (items != null) { }"); // never terminates, still compiles
        SolutionVerifier.Result hang = SolutionVerifier.verify(hw1, Map.of("MyLinkedList.java", loop), Duration.ofSeconds(3));
        assertFalse(hang.verified());
        assertTrue(hang.detail().contains("did not finish"), hang.detail());
    }

    @Test
    void hw8StyleCheckRunsIndependentSortHarness() throws IOException {
        AssignmentMaterials hw8 = AssignmentMaterials.load(root, "Hw8");
        assertTrue(hw8.expectedOutput().isEmpty());
        Path control = Path.of("negative-controls/Hw8/Hw8-N01");
        Map<String, String> files = new java.util.LinkedHashMap<>();
        for (String f : List.of("LinkedList.java", "Queue.java", "Tester.java")) {
            files.put(f, Files.readString(control.resolve(f)));
        }
        assertTrue(SolutionVerifier.verify(hw8, files, Duration.ofSeconds(60)).verified());
        // break MergeSort's ordering: the Tester's own isSorted() may be fooled, the harness is not
        files.put("LinkedList.java", files.get("LinkedList.java").replace("if (fa < fb) {", "if (fa > fb) {"));
        SolutionVerifier.Result r = SolutionVerifier.verify(hw8, files, Duration.ofSeconds(60));
        assertFalse(r.verified(), r.detail());
    }

    @Test
    void generatorRetriesWithFeedbackAndRecordsProvenance() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        MockAdapter mock = new MockAdapter("mock/model:1", req -> {
            int n = calls.incrementAndGet();
            if (n == 2) {
                assertTrue(req.userPrompt().contains("failed automatic verification"), "feedback is sent");
            }
            ObjectNode o = JSON.createObjectNode();
            o.put("notes", "attempt " + n);
            o.putArray("files").addObject().put("name", "MyLinkedList.java").put("content", n == 1 ? WRONG : GOOD);
            return o.toString();
        });
        Path out = root.resolve("references");
        ReferenceGenerator gen = new ReferenceGenerator(mock, 1000, null, 3, Duration.ofSeconds(30));
        ReferenceGenerator.Outcome o = gen.generate(hw1, out, false);
        assertTrue(o.verified());
        assertEquals(2, o.attempts());
        Path dir = out.resolve("Hw1/mock-model-1");
        assertEquals(dir, o.dir());
        JsonNode p = JSON.readTree(dir.resolve("provenance.json").toFile());
        assertFalse(p.path("human_reviewed").asBoolean(), "human review is never assumed");
        assertTrue(p.path("automatic_verification").path("verified").asBoolean());
        assertTrue(p.path("prompt_version").asText().startsWith("reference_solution_v1@"));
        assertFalse(Files.exists(dir.resolve("MyLinkedListTester.java")), "supplied tester is not part of the reference");
        assertThrows(IllegalStateException.class, () -> gen.generate(hw1, out, false), "no silent overwrite");
    }

    @Test
    void unverifiedResultIsKeptApart() {
        MockAdapter alwaysWrong = new MockAdapter("bad-model", req -> {
            ObjectNode o = JSON.createObjectNode();
            o.put("notes", "");
            o.putArray("files").addObject().put("name", "MyLinkedList.java").put("content", WRONG);
            return o.toString();
        });
        ReferenceGenerator.Outcome o = new ReferenceGenerator(alwaysWrong, 1000, null, 2, Duration.ofSeconds(30))
                .generate(hw1, root.resolve("references"), false);
        assertFalse(o.verified());
        assertTrue(o.dir().getFileName().toString().endsWith(".unverified"));
    }

    private static byte[] docx(String... paragraphs) throws IOException {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?><w:document><w:body>");
        for (String p : paragraphs) {
            xml.append("<w:p><w:r><w:t>").append(p.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                    .append("</w:t></w:r></w:p>");
        }
        xml.append("</w:body></w:document>");
        var bytes = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            z.putNextEntry(new ZipEntry("word/document.xml"));
            z.write(xml.toString().getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return bytes.toByteArray();
    }
}
