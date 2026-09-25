package rdt; 
public final class GoBackNTest { 
    private GoBackNTest() {} 
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
        private void windowTooLargeRejected() { 
            boolean threw = false; 
            try { 
                new GoBackN(null, null, 1 << 16); 
            } 
            catch (IllegalArgumentException e) { 
                threw = true; 
            } 
            check("windowSize > 2^k-1 is rejected", threw); 
        } 
        private void windowOneAccepted() { 
            boolean ok = false; 
            try { 
                new GoBackN(null, null, 1); 
                ok = true; 
            } 
            catch (Exception e) {} 
            check("windowSize = 1 is accepted", ok); 
        } 
        private void fixedRtoAccepted() { 
            boolean ok = false; 
            try { 
                new GoBackN(null, null, 8, 500L); 
                ok = true; 
            } 
            catch (Exception e) {} 
            check("fixed-RTO constructor accepted", ok); 
        } 
        private void windowMaxAccepted() { 
            boolean ok = false; 
            try { 
                new GoBackN(null, null, (1 << 16) - 1); 
                ok = true; 
            } 
            catch (Exception e) {} 
            check("windowSize = 2^k-1 is accepted", ok);
        } 
        private void run() { 
            windowTooLargeRejected(); 
            windowOneAccepted(); 
            fixedRtoAccepted();
            windowMaxAccepted(); 
            System.out.println(); 
            System.out.println("Passed: " + passed); 
            System.out.println("Failed: " + failed); 
            if (failed != 0) 
                throw new AssertionError("GoBackNTest failed: " + failed + " test(s)"); 
        } 
    } 
    public static void main(String[] args) { 
        new TestHarness().run(); 
    } 
}
