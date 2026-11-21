package aiaa.data;

import aiaa.pdm.SourceFiles;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * What the instructor handed out for one homework: description text, starter code, supplied testers
 * and (when the description contains it) the expected program output.
 *
 * @param starterFiles    starter code files other than the supplied testers (file name -> content)
 * @param testerFiles     supplied tester files that must be compiled unchanged with a solution
 * @param expectedOutput  non-blank lines of the "CORRECT OUTPUT" section, or empty if the description has none
 */
public record AssignmentMaterials(HomeworkSpec homework, String description, Map<String, String> starterFiles,
                                  Map<String, String> testerFiles, List<String> expectedOutput) {

    private static final Pattern PARAGRAPH = Pattern.compile("<w:p[ >].*?</w:p>", Pattern.DOTALL);

    public static AssignmentMaterials load(Path dataRoot, String homework) {
        HomeworkSpec spec = HomeworkSpec.forName(homework);
        if (spec.description() == null) {
            throw new IllegalArgumentException("No description known for " + spec.name());
        }
        Path descriptions = dataRoot.resolve(DataLayout.DESCRIPTIONS);
        String text = docxText(readDocument(descriptions, spec.description()));

        Map<String, String> starter = new LinkedHashMap<>();
        Map<String, String> testers = new LinkedHashMap<>();
        if (spec.starterFile() != null) {
            Path zip = descriptions.resolve(spec.starterFile());
            SourceFiles.readJava(zip).forEach((path, content) -> {
                String name = path.substring(path.lastIndexOf('/') + 1);
                (spec.excludedFiles().contains(name) ? testers : starter).put(name, content);
            });
        }
        return new AssignmentMaterials(spec, text, starter, testers, expectedOutput(text));
    }

    /** Lines after a "CORRECT OUTPUT" heading, trimmed, blank lines removed. */
    static List<String> expectedOutput(String description) {
        int at = description.indexOf("CORRECT OUTPUT");
        if (at < 0) {
            return List.of();
        }
        String rest = description.substring(at);
        rest = rest.substring(rest.indexOf('\n') + 1);
        return rest.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
    }

    private static byte[] readDocument(Path descriptions, String location) {
        try {
            int bang = location.indexOf('!');
            if (bang < 0) {
                return Files.readAllBytes(descriptions.resolve(location));
            }
            try (ZipFile zf = new ZipFile(descriptions.resolve(location.substring(0, bang)).toFile())) {
                ZipEntry e = zf.getEntry(location.substring(bang + 1));
                if (e == null) {
                    throw new IllegalArgumentException("No " + location);
                }
                return zf.getInputStream(e).readAllBytes();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read description " + location, e);
        }
    }

    /** Plain text of a .docx: one line per paragraph, XML entities decoded. */
    public static String docxText(byte[] docx) {
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().equals("word/document.xml")) {
                    String xml = new String(z.readAllBytes(), StandardCharsets.UTF_8);
                    StringBuilder sb = new StringBuilder();
                    Matcher m = PARAGRAPH.matcher(xml);
                    while (m.find()) {
                        String p = m.group().replaceAll("<w:tab/>", "\t").replaceAll("<w:br/>", "\n")
                                .replaceAll("<[^>]+>", "");
                        sb.append(p.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                                .replace("&apos;", "'").replace("&amp;", "&")).append('\n');
                    }
                    return sb.toString();
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Not a valid .docx", ex);
        }
        throw new IllegalArgumentException("Not a .docx (no word/document.xml)");
    }
}
