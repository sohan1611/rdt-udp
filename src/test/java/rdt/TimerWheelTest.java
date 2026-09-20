package rdt; 
public final class TimerWheelTest { 
    private TimerWheelTest() {} 
    private static final class Harness { 
        private int passed = 0; 
        private int failed = 0; 
        private void check(String name, boolean condition) { 
            if (condition) { 
                System.out.println("PASS: " + name); passed++; 
            }
            else {
                 System.out.println("FAIL: " + name); failed++; 
            } 
        } 
        private void earliestDeadlineIsMinimum() { 
            TimerWheel wheel = new TimerWheel();
            long minDeadline = Long.MAX_VALUE; 
            for (int i = 0; i < 10; i++) { 
               long delay = 100 + (long) (Math.random() * 900); 
               TimerWheel.TimerHandle h = wheel.schedule(delay, "t" + i); 
               if (h.deadlineNanos() < minDeadline) 
                minDeadline = h.deadlineNanos(); 
            } 
            check("earliestDeadline() returns the minimum deadline under 10 random insertions", wheel.earliestDeadline() == minDeadline); 
        } 
        private void pollNullAfterCancelFired() throws InterruptedException { 
            TimerWheel wheel = new TimerWheel(); 
            TimerWheel.TimerHandle h = wheel.schedule(1, "fire"); 
            Thread.sleep(5); 
            wheel.cancel(h); 
            check("poll() returns null after cancelling an already-fired timer", wheel.poll() == null); 
        } 
        private void identicalDeadlinesBothRetrievable() throws InterruptedException { 
            TimerWheel wheel = new TimerWheel(); 
            wheel.schedule(1, "a"); 
            wheel.schedule(1, "b"); 
            Thread.sleep(5); 
            TimerWheel.TimerHandle first = wheel.poll(); 
            TimerWheel.TimerHandle second = wheel.poll(); 
            check("both timers with identical deadlines are retrievable via poll()", first != null && second != null); 
        } 
        private void run() { 
            try { 
                earliestDeadlineIsMinimum(); 
                pollNullAfterCancelFired(); 
                identicalDeadlinesBothRetrievable(); 
            } 
            catch (InterruptedException e) { 
                Thread.currentThread().interrupt(); 
                throw new AssertionError("interrupted", e); 
            } 
            System.out.println(); 
            System.out.println("Passed: " + passed); 
            System.out.println("Failed: " + failed); 
            if (failed != 0) 
                throw new AssertionError("TimerWheelTest failed: " + failed + " test(s)"); 
        } 
    } 
    public static void main(String[] args) {
         new Harness().run(); 
    } 
}