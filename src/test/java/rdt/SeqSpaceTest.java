package rdt;

public final class SeqSpaceTest {

    private SeqSpaceTest() {}

    private static final class Harness {

        private int passed = 0;
        private int failed = 0;

        private void check(String name, boolean condition) {
            if (condition) {
                System.out.println("PASS: " + name);
                passed++;
            } else {
                System.out.println("FAIL: " + name);
                failed++;
            }
        }

        private void workedExample() {
            long M = 8;
            long base = 6;
            long N = 4;

            check("worked example: s=1 is current",
                    SeqSpace.inCurrentWindow(1, base, N, M));

            check("worked example: s=2 is previous",
                    SeqSpace.inPreviousWindow(2, base, N, M));

            check("worked example: s=4 is previous",
                    SeqSpace.inPreviousWindow(4, base, N, M));
        }

        private void percentVsFloorMod() {
            check("(1 - 3) % 4 == -2",
                    (1 - 3) % 4 == -2);

            check("floorMod(1 - 3, 4) == 2",
                    Math.floorMod(1 - 3, 4) == 2);

            check("offset uses floorMod across wrap",
                    SeqSpace.offset(1, 3, 4) == 2);
        }

        private void windowEdges() {
            long M = 8;
            long base = 6;
            long N = 4;

            check("current window: offset 0",
                    SeqSpace.offset(6, base, M) == 0
                    && SeqSpace.inCurrentWindow(6, base, N, M));

            check("current window: offset N-1",
                    SeqSpace.offset(1, base, M) == 3
                    && SeqSpace.inCurrentWindow(1, base, N, M));

            check("previous window: distance 1",
                    SeqSpace.inPreviousWindow(5, base, N, M));

            check("previous window: distance N",
                    SeqSpace.inPreviousWindow(2, base, N, M));
        }

        private void previousWindowClassification() {
            long M = 8;
            long base = 6;
            long N = 4;

            check("s=4 is not current",
                    !SeqSpace.inCurrentWindow(4, base, N, M));

            check("s=4 is previous",
                    SeqSpace.inPreviousWindow(4, base, N, M));

            check("s=3 is previous",
                    SeqSpace.inPreviousWindow(3, base, N, M));

            check("s=1 is not previous",
                    !SeqSpace.inPreviousWindow(1, base, N, M));
        }

        private void wraparound32Bit() {
            final long M = 4294967296L;
            final long base = 4294967294L;
            final long N = 4;

            check("32-bit: 4294967294 is current",
                    SeqSpace.inCurrentWindow(
                            4294967294L, base, N, M));

            check("32-bit: 4294967295 is current",
                    SeqSpace.inCurrentWindow(
                            4294967295L, base, N, M));

            check("32-bit: 0 is current after wrap",
                    SeqSpace.inCurrentWindow(
                            0, base, N, M));

            check("32-bit: 1 is current after wrap",
                    SeqSpace.inCurrentWindow(
                            1, base, N, M));

            check("32-bit: 2 is outside current window",
                    !SeqSpace.inCurrentWindow(
                            2, base, N, M));
        }

        private void illegalWindow() {
            long M = 8;
            long base = 6;
            long N = 5;

            check("illegal window: s=2 is current",
                    SeqSpace.inCurrentWindow(2, base, N, M));

            check("illegal window: s=2 is also previous",
                    SeqSpace.inPreviousWindow(2, base, N, M));
        }

        private void run() {
            workedExample();
            percentVsFloorMod();
            windowEdges();
            previousWindowClassification();
            wraparound32Bit();
            illegalWindow();

            System.out.println();
            System.out.println("Passed: " + passed);
            System.out.println("Failed: " + failed);

            if (failed != 0) {
                throw new AssertionError(
                        "SeqSpaceTest failed: " + failed + " test(s)");
            }
        }
    }

    public static void main(String[] args) {
        new Harness().run();
    }
}