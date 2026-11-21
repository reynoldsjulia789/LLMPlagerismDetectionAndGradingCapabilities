package aiaa.synth;

/** Gives tests in other packages access to the synth fixtures and CLI. */
public final class SynthTestSupport {

    private SynthTestSupport() {
    }

    public static String list() {
        return TransformationsTest.LIST;
    }

    public static String tester() {
        return TransformationsTest.TESTER;
    }

    public static String other() {
        return SyntheticGeneratorTest.OTHER;
    }

    public static void runGenerator(String[] args) {
        SynthMain.run(args);
    }
}
