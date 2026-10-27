package net.siftvanilla.siftcore.feature.bounties;

import java.time.Duration;

/** The numbers of bounties: the claim tax, expiry and overflow-safe sums. Pure. */
final class BountyMath {

    /** How a claimed bounty is split: the killer gets {@code payout}, {@code tax} is destroyed. */
    record Split(long total, long tax, long payout) {
    }

    private BountyMath() {
    }

    /** {@code percent} of {@code total}, rounded down, without overflowing for any total. */
    static long tax(long total, int percent) {
        if (total <= 0 || percent <= 0) {
            return 0;
        }
        if (percent >= 100) {
            return total;
        }
        return total / 100 * percent + total % 100 * percent / 100;
    }

    static Split split(long total, int percent) {
        long tax = tax(total, percent);
        return new Split(total, tax, total - tax);
    }

    /** When a contribution placed at {@code created} runs out. */
    static long expiresAt(long created, Duration expireAfter) {
        return add(created, expireAfter.toMillis());
    }

    /** True once a contribution placed at {@code created} has run out. */
    static boolean expired(long created, long now, Duration expireAfter) {
        return now >= expiresAt(created, expireAfter);
    }

    /** a + b, saturating at {@link Long#MAX_VALUE} (amounts and times are never negative here). */
    static long add(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }
}
