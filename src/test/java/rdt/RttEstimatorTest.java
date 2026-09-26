package rdt;

public final class RttEstimatorTest { 
    private RttEstimatorTest() {} 
    private static final class TestHarness { 
        private int passed = 0; 
        private int failed = 0; 
        private void check(String name, boolean condition) { 
            if (condition) { 
                System.out.println("PASS: " + name); 
                passed++; 
            } 
            else { 
                System.out.println("FAIL: " + name); 
                failed++; 
            } 
        } 
        private void firstSample() { 
            RttEstimator e = new RttEstimator();
            e.update(100_000_000L); 
            check("first sample: RTO = 300 ms", e.rtoMs() == 300L); 
        } 
        private void secondSample() { 
            RttEstimator e = new RttEstimator(); 
            e.update(100_000_000L); 
            e.update(200_000_000L); 
            check("second sample: RTO = 363 ms (hand-worked, RFC 6298 order)", e.rtoMs() == 363L); 
        } 
        private void doubleRto() { 
            RttEstimator e = new RttEstimator(); 
            e.update(100_000_000L); 
            long before = e.rtoMs(); 
            e.doubleRto(); 
            check("doubleRto() doubles the RTO", e.rtoMs() == before * 2); 
        } 
        private void minRtoIsRespected() { 
            RttEstimator e = new RttEstimator(5L); 
            e.update(1_000_000L); 
            check("RTO never falls below minRtoMs", e.rtoMs() >= 5L); 
        } 
        private void maxRto() { 
            RttEstimator e = new RttEstimator(); 
            for (int i = 0; i < 30; i++) 
                e.doubleRto(); 
            check("RTO never exceeds 60 000 ms", e.rtoMs() <= 60_000L); 
        }
        private void backoffResets() { 
            RttEstimator e = new RttEstimator(); 
            e.update(100_000_000L); 
            long base = e.rtoMs(); 
            e.doubleRto(); 
            e.doubleRto(); 
            e.resetBackoff(); 
            check("resetBackoff() restores base RTO", e.rtoMs() == base); 
        } 
        private void run() {
             firstSample(); 
             secondSample(); 
             doubleRto(); 
             minRtoIsRespected(); 
             maxRto(); 
             backoffResets();
             System.out.println(); 
             System.out.println("Passed: " + passed); 
             System.out.println("Failed: " + failed); 
             if (failed != 0) 
                throw new AssertionError("RttEstimatorTest failed: " + failed + " test(s)"); 
        } 
    } 
    public static void main(String[] args) { 
        new TestHarness().run(); 
    } 
}