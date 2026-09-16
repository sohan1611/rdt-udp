package rdt;

final class SeqSpaceTestHelper {

    private SeqSpaceTestHelper() {}

    public static long offset(long s, long base, long M) {
        return Math.floorMod(s - base, M);
    }

    public static boolean inCurrentWindow(long s, long base, long N, long M) {
        return offset(s, base, M) < N;
    }

    public static boolean inPreviousWindow(long s, long base, long N, long M) {
        long distance = Math.floorMod(base - s, M);
        return distance >= 1 && distance <= N;
    }
}