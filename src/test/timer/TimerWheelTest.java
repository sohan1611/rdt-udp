package timer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import main.timer.TimerWheel;
import main.timer.TimerWheel.TimerHandle;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class TimerWheelTest {

    // ------------------------------------------------------------------
    // Test 1: Ordering under many insertions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("earliestDeadline() returns the minimum deadline under 10 random insertions")
    void testOrderingUnderManyInsertions() throws InterruptedException {
        TimerWheel wheel = new TimerWheel();
        Random rng = new Random(42);

        long minDeadline = Long.MAX_VALUE;

        for (int i = 0; i < 10; i++) {
            long delayMs = 100 + rng.nextInt(901);
            long beforeSchedule = System.nanoTime();
            wheel.schedule(delayMs, "pkt:" + i);

            long lowerBound = beforeSchedule + delayMs * 1_000_000L;
            if (lowerBound < minDeadline) {
                minDeadline = lowerBound;
            }
        }

        long reported = wheel.earliestDeadline();

        assertNotEquals(Long.MAX_VALUE, reported,
            "earliestDeadline() must not return MAX_VALUE when timers are pending");

        assertTrue(reported >= minDeadline,
            "earliestDeadline() reported a deadline earlier than any we scheduled");

        wheel.schedule(1, "anchor");
        long afterAnchor = wheel.earliestDeadline();
        assertTrue(afterAnchor <= reported,
            "After inserting a 1ms anchor, earliestDeadline() should be <= previous minimum");
    }

    // ------------------------------------------------------------------
    // Test 2: Cancelling an already-fired timer
    // ------------------------------------------------------------------

    @Test
    @DisplayName("poll() returns null after cancelling an already-fired timer")
    void testCancelAlreadyFiredTimer() throws InterruptedException {
        TimerWheel wheel = new TimerWheel();

        TimerWheel.TimerHandle handle = wheel.schedule(1, "fired-packet");

        Thread.sleep(10);

        assertTrue(wheel.timeUntilNextMs() == 0,
            "Timer should have already expired after 10ms sleep");

        wheel.cancel(handle);

        TimerWheel.TimerHandle result = wheel.poll();

        assertNull(result,
            "poll() must return null when the only pending timer was cancelled");

        assertTrue(wheel.isEmpty(),
            "Wheel must be empty after the only cancelled timer is purged");
    }

    // ------------------------------------------------------------------
    // Test 3: Identical deadlines
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Both timers with identical deadlines are retrievable via poll()")
    void testIdenticalDeadlines() throws InterruptedException {
        TimerWheel wheel = new TimerWheel();

        TimerWheel.TimerHandle h1 = wheel.schedule(5, "seq=1");
        TimerWheel.TimerHandle h2 = wheel.schedule(5, "seq=2");

        assertEquals(2, wheel.size(),
            "Heap must contain exactly 2 entries after two schedules");

        Thread.sleep(20);

        TimerWheel.TimerHandle first = wheel.poll();
        assertNotNull(first,
            "First poll() must return a handle after both timers fired");

        TimerWheel.TimerHandle second = wheel.poll();
        assertNotNull(second,
            "Second poll() must return a handle — both timers must be retrievable");

        assertNull(wheel.poll(),
            "Third poll() must return null — no more timers remain");

        assertTrue(wheel.isEmpty(),
            "Wheel must be empty after both identical-deadline timers are polled");
    }
}
