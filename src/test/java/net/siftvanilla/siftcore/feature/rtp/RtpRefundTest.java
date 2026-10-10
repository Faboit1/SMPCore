package net.siftvanilla.siftcore.feature.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * Dupe audit R13: a random teleport that does not happen pays its charge back, but only a charge that was stored. When
 * storing the charge failed, the ledger already gave the money back, and a refund on top would create it.
 */
class RtpRefundTest {

    private final List<String> steps = new ArrayList<>();

    private void afterCharge(CompletableFuture<Void> stored) {
        RtpService.afterCharge(stored, () -> this.steps.add("refund"), () -> this.steps.add("nothing to refund"));
    }

    @Test
    void aStoredChargeIsPaidBackOnce() {
        afterCharge(CompletableFuture.completedFuture(null));
        assertEquals(List.of("refund"), this.steps);
    }

    @Test
    void aChargeThatFailedToStoreIsNotPaidBackAgain() {
        afterCharge(CompletableFuture.failedFuture(new SQLException("disk on fire")));
        assertEquals(List.of("nothing to refund"), this.steps);
    }

    @Test
    void theRefundWaitsForTheChargeToBeStored() {
        CompletableFuture<Void> stored = new CompletableFuture<>();
        afterCharge(stored);
        assertEquals(List.of(), this.steps, "nothing happens while the charge waits for storage");
        stored.completeExceptionally(new SQLException("lock timeout"));
        assertEquals(List.of("nothing to refund"), this.steps);

        CompletableFuture<Void> later = new CompletableFuture<>();
        afterCharge(later);
        later.complete(null);
        assertEquals(List.of("nothing to refund", "refund"), this.steps);
    }
}
