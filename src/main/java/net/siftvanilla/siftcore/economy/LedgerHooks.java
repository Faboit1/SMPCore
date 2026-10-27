package net.siftvanilla.siftcore.economy;

/** Bridges the ledger to the outside world (Bukkit events) without making the ledger depend on the server. */
public interface LedgerHooks {

    LedgerHooks NONE = new LedgerHooks() {
        @Override
        public boolean allow(LedgerTx tx) {
            return true;
        }

        @Override
        public void committed(CommittedTx tx) {
        }
    };

    /** Fires the cancellable pre-event; returns false if a listener cancelled it. */
    boolean allow(LedgerTx tx);

    /** Called after the transaction is durably committed, off the world threads. */
    void committed(CommittedTx tx);
}
