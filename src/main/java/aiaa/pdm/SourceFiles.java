package aiaa.pdm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads Java sources from a directory, a single file, or a .zip archive. */
public final class SourceFiles {

    private static final Pattern TYPE_DECL = Pattern.compile(
            "(?m)^\\s*(?:(?:public|final|abstract|sealed|non-sealed|static)\\s+)*(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");
    /** Identity markers, only inside comment lines so code such as {@code for (String name : names)} is untouched. */
    private static final Pattern IDENTITY_COMMENT = Pattern.compile(
            "(?im)^(\\s*(?://|/\\*+|\\*).*?)(@author\\b|\\bauthor\\s*:|\\b(?:student\\s*)?name\\s*:|\\bstudent\\s*id\\s*:)(.*)$");

    private SourceFiles() {
    }

    /**
     * Reads every {@code .java} file under {@code path} (directory, zip, or single file),
     * skipping hidden entries, IDE folders and macOS metadata. Keys are paths relative to the root.
     */
    public static Map<String, String> readJava(Path path) {
        try {
            if (Files.isDirectory(path)) {
                return readDirectory(path);
            }
            if (path.getFileName().toString().toLowerCase().endsWith(".zip")) {
                return readZip(path);
            }
            Map<String, String> single = new TreeMap<>();
            single.put(path.getFileName().toString(), decode(Files.readAllBytes(path)));
            return single;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read sources from " + path, e);
        }
    }

    private static Map<String, String> readDirectory(Path root) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                Path rel = root.relativize(p);
                if (isIgnored(rel.toString().replace('\\', '/')) || !p.toString().endsWith(".java")) {
                    continue;
                }
                out.put(rel.toString().replace('\\', '/'), decode(Files.readAllBytes(p)));
            }
        }
        return out;
    }

    private static Map<String, String> readZip(Path zip) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (ZipEntry e : zf.stream().toList()) {
                if (e.isDirectory() || isIgnored(e.getName()) || !e.getName().endsWith(".java")) {
                    continue;
                }
                try (var in = zf.getInputStream(e)) {
                    out.put(e.getName(), decode(in.readAllBytes()));
                }
            }
        }
        return out;
    }

    static boolean isIgnored(String relPath) {
        for (String segment : relPath.split("/")) {
            if (segment.startsWith(".") || segment.equals("__MACOSX") || segment.equals("out")
                    || segment.equals("target") || segment.equals("bin")) {
                return true;
            }
        }
        return false;
    }

    /** UTF-8 when valid, otherwise Windows-1252 (common for student files). */
    static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString().replace("﻿", "");
        } catch (CharacterCodingException e) {
            return new String(bytes, java.nio.charset.Charset.forName("windows-1252"));
        }
    }

    /**
     * Renames files to identity-free display names. A file is shown by its own name when that
     * matches its first declared type; otherwise by the declared type (student files are often
     * named like {@code Firstname_ClassName.java}); otherwise {@code FileN.java}.
     * Directory prefixes are dropped (they may contain names too).
     */
    public static Map<String, String> withDisplayNames(Map<String, String> files) {
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        int n = 0;
        for (Map.Entry<String, String> e : files.entrySet()) {
            n++;
            String base = e.getKey().substring(e.getKey().lastIndexOf('/') + 1);
            String stem = base.endsWith(".java") ? base.substring(0, base.length() - 5) : base;
            Matcher m = TYPE_DECL.matcher(e.getValue());
            String name;
            if (m.find()) {
                name = m.group(1).equals(stem) ? stem : m.group(1);
            } else {
                name = "File" + n;
            }
            String candidate = name + ".java";
            for (int k = 2; !used.add(candidate); k++) {
                candidate = name + "_" + k + ".java";
            }
            out.put(candidate, e.getValue());
        }
        return out;
    }

    public record Scrubbed(String text, int redactions) {
    }

    /**
     * Best-effort removal of e-mail addresses and author/name/student-ID comment values.
     * This is a safety net, not a guarantee: names in free-form comments are not detected.
     */
    public static Scrubbed scrubIdentity(String source) {
        int count = 0;
        Matcher em = EMAIL.matcher(source);
        StringBuilder sb = new StringBuilder();
        while (em.find()) {
            count++;
            em.appendReplacement(sb, "[REDACTED_EMAIL]");
        }
        em.appendTail(sb);
        Matcher im = IDENTITY_COMMENT.matcher(sb.toString());
        StringBuilder sb2 = new StringBuilder();
        while (im.find()) {
            count++;
            im.appendReplacement(sb2, Matcher.quoteReplacement(im.group(1) + im.group(2) + " [REDACTED]"));
        }
        im.appendTail(sb2);
        return new Scrubbed(sb2.toString(), count);
    }

    /** Lists the immediate, non-hidden subdirectories, ordered by leading number then name. */
    public static List<Path> submissionFolders(Path assignmentDir) {
        try (Stream<Path> list = Files.list(assignmentDir)) {
            List<Path> dirs = new ArrayList<>(list
                    .filter(Files::isDirectory)
                    .filter(p -> !isIgnored(p.getFileName().toString()))
                    .toList());
            dirs.sort((a, b) -> {
                int c = Long.compare(leadingNumber(a), leadingNumber(b));
                return c != 0 ? c : a.getFileName().toString().compareTo(b.getFileName().toString());
            });
            return dirs;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + assignmentDir, e);
        }
    }

    private static long leadingNumber(Path p) {
        Matcher m = Pattern.compile("^(\\d+)").matcher(p.getFileName().toString());
        return m.find() ? Long.parseLong(m.group(1)) : Long.MAX_VALUE;
    }
}
