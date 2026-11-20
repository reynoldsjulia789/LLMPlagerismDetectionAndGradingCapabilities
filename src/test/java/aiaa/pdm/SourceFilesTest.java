package aiaa.pdm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceFilesTest {

    @Test
    void displayNamesHideNamePrefixedFileNames() {
        Map<String, String> files = new TreeMap<>();
        files.put("JaneDoe_CDoublyLinkedList.java", "public class CDoublyLinkedList {}");
        files.put("src/Tester.java", "class Tester {}");
        files.put("notes.java", "// nothing declared");
        Map<String, String> named = SourceFiles.withDisplayNames(files);
        assertEquals(List.of("CDoublyLinkedList.java", "File2.java", "Tester.java"), List.copyOf(named.keySet()));
    }

    @Test
    void duplicateDisplayNamesAreDisambiguated() {
        Map<String, String> files = new TreeMap<>();
        files.put("a/MyLinkedList.java", "public class MyLinkedList {}");
        files.put("b/MyLinkedList.java", "public class MyLinkedList {}");
        assertEquals(List.of("MyLinkedList.java", "MyLinkedList_2.java"),
                List.copyOf(SourceFiles.withDisplayNames(files).keySet()));
    }

    @Test
    void scrubbingRedactsCommentIdentityButNotCode() {
        String src = """
                /**
                 * @author Jane Doe
                 * Name: Jane Doe, jane.doe@example.edu
                 */
                public class A {
                    void f(List<String> names) { for (String name : names) { } }
                }
                """;
        SourceFiles.Scrubbed s = SourceFiles.scrubIdentity(src);
        assertFalse(s.text().contains("Jane"));
        assertFalse(s.text().contains("example.edu"));
        assertTrue(s.text().contains("for (String name : names)"), "code untouched");
        assertEquals(3, s.redactions());
    }

    @Test
    void submissionFoldersAreNumericallyOrderedAndHiddenOnesSkipped(@TempDir Path dir) throws IOException {
        for (String d : List.of("10_x", "2_y", "1_z", ".java-extract.ABC")) {
            Files.createDirectories(dir.resolve(d));
        }
        List<String> names = SourceFiles.submissionFolders(dir).stream().map(p -> p.getFileName().toString()).toList();
        assertEquals(List.of("1_z", "2_y", "10_x"), names);
    }

    @Test
    void readJavaSkipsIdeAndHiddenFolders(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve(".idea"));
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve(".idea/Junk.java"), "class Junk {}");
        Files.writeString(dir.resolve("src/A.java"), "class A {}");
        Files.writeString(dir.resolve("readme.txt"), "x");
        assertEquals(List.of("src/A.java"), List.copyOf(SourceFiles.readJava(dir).keySet()));
    }
}
