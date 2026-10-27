package net.siftvanilla.siftcore.feature.auction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The shutdown wait for trades whose storage answer is still on its way. */
class InFlightTest {

    @Test
    void idleWhenNothingWaits() {
        InFlight work = new InFlight();
        assertTrue(work.awaitIdle(Duration.ZERO));
        work.begin();
        work.end();
        assertEquals(0, work.count());
        assertTrue(work.awaitIdle(Duration.ZERO));
    }

    @Test
    void givesUpAfterTheTimeout() {
        InFlight work = new InFlight();
        work.begin();
        long start = System.nanoTime();
        assertFalse(work.awaitIdle(Duration.ofMillis(60)));
        assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(60));
        assertEquals(1, work.count());
    }

    @Test
    void returnsWhenTheLastAnswerArrives() throws Exception {
        InFlight work = new InFlight();
        work.begin();
        work.begin();
        CountDownLatch waiting = new CountDownLatch(1);
        CompletableFuture<Boolean> idle = CompletableFuture.supplyAsync(() -> {
            waiting.countDown();
            return work.awaitIdle(Duration.ofSeconds(10));
        });
        assertTrue(waiting.await(5, TimeUnit.SECONDS));
        work.end();
        Thread.sleep(30);
        assertFalse(idle.isDone(), "one answer is still missing");
        work.end();
        assertTrue(idle.get(5, TimeUnit.SECONDS));
    }

    @Test
    void anUnpairedEndIsAnError() {
        InFlight work = new InFlight();
        assertThrows(IllegalStateException.class, work::end);
        assertEquals(0, work.count());
    }
}
