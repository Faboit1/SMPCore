package net.siftvanilla.siftcore.feature.auction;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts work that is waiting for storage to answer before it hands items over (a claim waiting for its commit, a
 * listing that may have to be returned), so shutdown can wait for those answers before it puts undelivered items back
 * into the claim box. Thread-safe.
 */
final class InFlight {

    private final AtomicInteger waiting = new AtomicInteger();

    /** Marks one piece of work as started; pair every call with exactly one {@link #end()}. */
    void begin() {
        this.waiting.incrementAndGet();
    }

    void end() {
        if (this.waiting.decrementAndGet() < 0) {
            this.waiting.incrementAndGet();
            throw new IllegalStateException("end() without begin()");
        }
    }

    int count() {
        return this.waiting.get();
    }

    /** Waits until nothing is in flight, at most {@code timeout}. Returns false when work was still waiting. */
    boolean awaitIdle(Duration timeout) {
        long end = System.nanoTime() + timeout.toNanos();
        while (this.waiting.get() > 0) {
            if (System.nanoTime() - end >= 0) {
                return false;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return this.waiting.get() <= 0;
            }
        }
        return true;
    }
}
