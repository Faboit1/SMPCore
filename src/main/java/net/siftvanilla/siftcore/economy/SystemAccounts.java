package net.siftvanilla.siftcore.economy;

import java.util.Set;
import java.util.UUID;

/**
 * Fixed accounts owned by the server. Money waiting in escrow is still part of the total supply, so it lives in
 * real accounts rather than disappearing between the two halves of a trade.
 */
public final class SystemAccounts {

    /** Holds the money of unfilled buy orders. */
    public static final UUID ORDERS_ESCROW = new UUID(0L, 0xE001L);
    /** Holds bounty money until it is claimed or refunded. */
    public static final UUID BOUNTY_ESCROW = new UUID(0L, 0xE002L);

    private static final Set<UUID> ALL = Set.of(ORDERS_ESCROW, BOUNTY_ESCROW);

    private SystemAccounts() {
    }

    public static boolean isSystem(UUID account) {
        return ALL.contains(account);
    }

    public static Set<UUID> all() {
        return ALL;
    }
}
