package rdt;
public final class RttEstimatorTest { 
    private RttEstimatorTest() {} 
    private static final class Harness { 
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
        
        // First sample: SRTT=R, RTTVAR=R/2, RTO=SRTT+4*RTTVAR = R+2R = 3R 
        private void firstSample() { 
            RttEstimator e = new RttEstimator(); 
            e.update(100); 
            check("first sample: RTO = 3 * R", e.rtoMs() == 300L); 
        } 
        
        // Second sample hand-worked: // R1=100, R2=200 // srtt = (7/8)*100 + (1/8)*200 = 87.5 + 25 = 112.5 // rttvar = (3/4)*50 + (1/4)*|112.5-200| = 37.5 + 21.875 = 59.375 // rto = 112.5 + 4*59.375 = 112.5 + 237.5 = 350 -> round = 350 
        private void secondSample() { 
            RttEstimator e = new RttEstimator(); 
            e.update(100); 
            e.update(200); 
            check("second sample: RTO matches hand-worked value (363 ms)", e.rtoMs() == 363L); 
        } 
        
        // doubleRto doubles it
        private void doubleRto() { 
            RttEstimator e = new RttEstimator(); 
            e.update(100); 
            long before = e.rtoMs(); 
            e.doubleRto(); 
            check("doubleRto() doubles the RTO", e.rtoMs() == before * 2); 
        } 
        
        // RTO is clamped at MIN (200 ms) 
        private void minRto() { 
            RttEstimator e = new RttEstimator(); 
            e.update(1); 
            check("RTO never falls below 200 ms", e.rtoMs() >= 200L); 
        } 
        
        // RTO is clamped at MAX (60 000 ms) 
        private void maxRto() { 
            RttEstimator e = new RttEstimator(); 
            for (int i = 0; i < 30; i++) 
                e.doubleRto(); 
            check("RTO never exceeds 60 000 ms", e.rtoMs() <= 60_000L); 
        } 
        private void run() { 
            firstSample(); 
            secondSample(); 
            doubleRto(); 
            minRto(); 
            maxRto(); 
            System.out.println(); 
            System.out.println("Passed: " + passed); 
            System.out.println("Failed: " + failed); 
            if (failed != 0) 
                throw new AssertionError("RttEstimatorTest failed: " + failed + " test(s)"); 
        } 
    } 
    public static void main(String[] args) { 
        new Harness().run(); 
    } 
}
