package rdt; 
public final class TimerWheelTest { 
    private TimerWheelTest() {} 
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
        private void earliestDeadlineIsMinimum() { 
            TimerWheel wheel = new TimerWheel(); 
            long minDeadline = Long.MAX_VALUE; 
            for (int i = 0; i < 10; i++) { 
                long delay = 100 + (long)(Math.random() * 900); 
                TimerWheel.TimerHandle h = wheel.schedule(delay, "t" + i); 
                if (h.deadlineNanos() < minDeadline)
                     minDeadline = h.deadlineNanos(); 
            } 
            check("earliestDeadline() returns the minimum deadline under 10 random insertions", wheel.earliestDeadline() == minDeadline); 
        } 
        private void cancelledTimerNeverFires() throws InterruptedException { 
            TimerWheel wheel = new TimerWheel(); 
            TimerWheel.TimerHandle h = wheel.schedule(1, "fire"); 
            wheel.cancel(h); 
            Thread.sleep(5); 
            check("cancelled timer never fires via poll()", wheel.poll() == null); 
        } 
        private void sizeAfterCancels() { 
            TimerWheel wheel = new TimerWheel(); 
            TimerWheel.TimerHandle[] handles = new TimerWheel.TimerHandle[5000]; 
            for (int i = 0; i < 5000; i++) 
                handles[i] = wheel.schedule(100, "t" + i); 
            for (int i = 0; i < 5000; i++) 
                wheel.cancel(handles[i]); 
            check("size() is 0 after all 5000 timers cancelled", wheel.size() == 0); 
            check("isEmpty() is true after all 5000 timers cancelled", wheel.isEmpty()); 
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
        private void pollNullBeforeDeadline() { 
            TimerWheel wheel = new TimerWheel(); 
            wheel.schedule(60_000, "far future"); 
            check("poll() returns null before deadline", wheel.poll() == null); 
        } 
        private void timeUntilNextMsEmptyHeap() { 
            TimerWheel wheel = new TimerWheel(); 
            check("timeUntilNextMs() returns 0 when heap is empty", wheel.timeUntilNextMs() == 0L); 
        } 
        private void timeUntilNextMsFitsInInt() { 
            TimerWheel wheel = new TimerWheel(); 
            wheel.schedule(1000, "t"); 
            long t = wheel.timeUntilNextMs(); 
            check("timeUntilNextMs() fits in an int when a timer is pending", t >= 1L && t <= (long) Integer.MAX_VALUE); 
        } 
        private void run() { 
            try { 
                earliestDeadlineIsMinimum(); 
                cancelledTimerNeverFires(); 
                sizeAfterCancels(); 
                identicalDeadlinesBothRetrievable(); 
                pollNullBeforeDeadline(); 
                timeUntilNextMsEmptyHeap(); 
                timeUntilNextMsFitsInInt(); 
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