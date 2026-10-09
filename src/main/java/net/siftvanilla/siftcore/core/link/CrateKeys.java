package net.siftvanilla.siftcore.core.link;

import java.util.Set;
import java.util.UUID;
import net.siftvanilla.siftcore.api.economy.TransactionResult;

/** Gives virtual crate keys. Implemented by the crates feature; used by the shard shop, store delivery and keyall. */
public interface CrateKeys {

    CrateKeys NONE = new CrateKeys() {
        @Override
        public Set<String> crates() {
            return Set.of();
        }

        @Override
        public TransactionResult give(UUID player, String crate, int amount, String actor, String ref) {
            return TransactionResult.failed(UUID.randomUUID(), net.siftvanilla.siftcore.api.economy.TransactionStatus.REJECTED, "no_crates");
        }

        @Override
        public int keys(UUID player, String crate) {
            return 0;
        }
    };

    /** Keys that look up {@code keys} on every call, for features built before the crates feature. */
    static CrateKeys late(java.util.function.Supplier<CrateKeys> keys) {
        return new CrateKeys() {
            @Override
            public Set<String> crates() {
                return keys.get().crates();
            }

            @Override
            public TransactionResult give(UUID player, String crate, int amount, String actor, String ref) {
                return keys.get().give(player, crate, amount, actor, ref);
            }

            @Override
            public int keys(UUID player, String crate) {
                return keys.get().keys(player, crate);
            }
        };
    }

    /** Ids of the configured crates. */
    Set<String> crates();

    /**
     * Gives keys atomically (stored before it returns success). {@code ref} makes the grant idempotent when not
     * null: a second grant with the same ref is rejected with reason {@code duplicate}.
     */
    TransactionResult give(UUID player, String crate, int amount, String actor, String ref);

    int keys(UUID player, String crate);
}
