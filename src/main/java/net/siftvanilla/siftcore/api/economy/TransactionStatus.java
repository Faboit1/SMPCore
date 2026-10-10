package net.siftvanilla.siftcore.api.economy;

/** The outcome of an economy transaction. */
public enum TransactionStatus {
    /** Applied. Its database commit may still be pending; see {@link TransactionResult#committed()}. */
    SUCCESS,
    /** An account lacked the funds; nothing was applied. */
    INSUFFICIENT_FUNDS,
    /** A credit would push an account above the maximum balance; nothing was applied. */
    BALANCE_LIMIT,
    /** A domain check failed (for example, the listing was already sold); nothing was applied. */
    REJECTED,
    /** A plugin cancelled the transaction event; nothing was applied. */
    CANCELLED,
    /** The economy is read-only after a storage failure, or shutting down; nothing was applied. */
    UNAVAILABLE
}
