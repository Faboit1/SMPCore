package net.siftvanilla.siftcore.api.economy;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Result of a transaction.
 *
 * @param id        the transaction id (shared by all its ledger rows)
 * @param status    the outcome
 * @param reason    a short machine reason when not successful (e.g. {@code listing_gone}), otherwise null
 * @param committed completes when the transaction is durably stored; completes exceptionally if storing failed,
 *                  in which case the transaction was reverted in memory. Callers that hand out items must wait for it.
 */
public record TransactionResult(UUID id, TransactionStatus status, String reason, CompletableFuture<Void> committed) {

    public boolean success() {
        return this.status == TransactionStatus.SUCCESS;
    }

    public static TransactionResult failed(UUID id, TransactionStatus status, String reason) {
        return new TransactionResult(id, status, reason, CompletableFuture.completedFuture(null));
    }
}
