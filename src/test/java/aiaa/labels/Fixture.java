package aiaa.labels;

import aiaa.synth.SynthTestSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A small end-to-end dataset for tests: a real-style Hw1 root (two terms, one student in both),
 * four negative controls, a generated synthetic root and its labels.
 */
public final class Fixture {

    public final Path dir;
    public final Path data;
    public final Path controls;
    public final Path synth;
    public final Path privateDir;
    public final Path labels;
    public final Labeler.Summary summary;

    private Fixture(Path dir, Labeler.Summary summary) {
        this.dir = dir;
        this.data = dir.resolve("data");
        this.controls = dir.resolve("controls");
        this.synth = dir.resolve("synthetic-data");
        this.privateDir = dir.resolve("private");
        this.labels = dir.resolve("labels");
        this.summary = summary;
    }

    public static Fixture build(Path dir, int sources, long seed) throws IOException {
        Path hw1 = dir.resolve("data/HomeworkSubmissions/Hw1");
        // student 111 submitted in both terms; 222, 333, 444 once each
        write(hw1.resolve("F25Hw1/1_111_a_1/MyLinkedList.java"), SynthTestSupport.list());
        write(hw1.resolve("F25Hw1/1_111_a_1/MyLinkedListTester.java"), SynthTestSupport.tester());
        write(hw1.resolve("F25Hw1/2_222_b_2/MyLinkedList.java"), SynthTestSupport.other());
        write(hw1.resolve("F25Hw1/3_333_c_3/MyLinkedList.java"), SynthTestSupport.other().replace("pos++", "pos += 1"));
        write(hw1.resolve("Sp26Hw1/1_111_d_1/MyLinkedList.java"), SynthTestSupport.list().replace("size++;", "size += 1;"));
        write(hw1.resolve("Sp26Hw1/2_444_e_2/MyLinkedList.java"), SynthTestSupport.other().replace("pos++", "++pos"));
        // more distinct originals per term, so partial copies have non-source hosts and families stay small
        for (int k = 4; k <= 9; k++) {
            String base = k % 2 == 0 ? SynthTestSupport.list() : SynthTestSupport.other();
            String extra = "    public int extra" + k + "(int x) {\n        int y = x * " + k + ";\n"
                    + "        for (int i = 0; i < " + k + "; i++) { y += i; }\n        return y;\n    }\n}\n";
            String code = base.substring(0, base.lastIndexOf('}')) + extra;
            write(hw1.resolve((k <= 6 ? "F25Hw1/" : "Sp26Hw1/") + k + "_" + (500 + k) + "_x_" + k + "/MyLinkedList.java"), code);
        }
        Path zip = dir.resolve("data/HomeworkDescriptions/hw1_description_startupcode.zip");
        Files.createDirectories(zip.getParent());
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("startupCode/MyLinkedList.java"));
            z.write("public class MyLinkedList<T> implements Iterable<T> {\n}\n".getBytes());
            z.closeEntry();
        }
        Path controls = dir.resolve("controls");
        // controls must be independent of every submission: array-backed designs unlike the linked fixtures
        for (int i = 1; i <= 4; i++) {
            write(controls.resolve("Hw1/Hw1-N0" + i + "/MyLinkedList.java"), arrayControl(i));
        }
        write(controls.resolve("Hw1/provenance.csv"), "control_id\nHw1-N01\n");

        Path synth = dir.resolve("synthetic-data");
        Path priv = dir.resolve("private");
        SynthTestSupport.runGenerator(new String[]{"--data-root", dir.resolve("data").toString(), "--homework", "Hw1",
                "--out-root", synth.toString(), "--private-dir", priv.toString(), "--sources", String.valueOf(sources)});
        Labeler.Summary summary = LabelMain.run(new String[]{"--data-root", synth.toString(), "--controls",
                controls.toString(), "--private-dir", priv.toString(), "--out", dir.resolve("labels").toString(),
                "--seed", String.valueOf(seed)});
        return new Fixture(dir, summary);
    }

    private static String arrayControl(int i) {
        String grow = i % 2 == 0
                ? "java.util.Arrays.copyOf(slots, slots.length * 2)"
                : "copyInto(new Object[slots.length + " + (8 * i) + "])";
        return """
                public class MyLinkedList<T> {
                    private Object[] slots = new Object[%d];
                    private int used;

                    private Object[] copyInto(Object[] target) {
                        System.arraycopy(slots, 0, target, 0, used);
                        return target;
                    }

                    public void addFirst(T value) {
                        if (used == slots.length) {
                            slots = %s;
                        }
                        System.arraycopy(slots, 0, slots, 1, used);
                        slots[0] = value;
                        used++;
                    }

                    @SuppressWarnings("unchecked")
                    public T pick(int where) {
                        if (where < 0 || where >= used) {
                            throw new IndexOutOfBoundsException("bad position " + where + " variant %d");
                        }
                        return (T) slots[where];
                    }

                    public boolean has(Object probe) {
                        int k = used;
                        while (k-- > 0) {
                            if (java.util.Objects.equals(slots[k], probe)) {
                                return true;
                            }
                        }
                        return false;
                    }
                }
                """.formatted(4 * i, grow, i);
    }

    static void write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }
}
