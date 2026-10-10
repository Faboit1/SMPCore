package net.siftvanilla.siftcore.feature.kits;

import java.time.Duration;
import java.util.Locale;
import net.siftvanilla.siftcore.core.config.Durations;

/**
 * How often a kit can be claimed: once per player ever, or again a fixed time after the last claim. Pure logic.
 *
 * @param every the time between two claims, or null for a kit that can be claimed only once
 */
public record Cooldown(Duration every) {

    /** One claim per player, ever. */
    static final Cooldown ONCE = new Cooldown(null);

    /** The shortest cooldown the config accepts. */
    static final Duration MIN = Duration.ofSeconds(1);

    /** The longest cooldown the config accepts. */
    static final Duration MAX = Duration.ofDays(365);

    public Cooldown {
        if (every != null && (every.compareTo(MIN) < 0 || every.compareTo(MAX) > 0)) {
            throw new IllegalArgumentException("must be between " + Durations.format(MIN) + " and " + Durations.format(MAX));
        }
    }

    static Cooldown every(Duration every) {
        return new Cooldown(every);
    }

    /**
     * Reads {@code once} or a duration such as {@code 24h} or {@code 1d12h}.
     *
     * @throws IllegalArgumentException with a short reason
     */
    static Cooldown parse(String text) {
        String value = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        if (value.equals("once")) {
            return ONCE;
        }
        Duration duration;
        try {
            duration = Durations.parse(value);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("is too long");
        }
        return every(duration);
    }

    public boolean once() {
        return this.every == null;
    }

    /**
     * Where a player stands with this kit.
     *
     * @param lastClaim when the player last claimed it (epoch milliseconds), or null when never
     * @param now       the current time (epoch milliseconds)
     */
    KitStatus status(Long lastClaim, long now) {
        if (lastClaim == null) {
            return KitStatus.READY;
        }
        if (this.every == null) {
            return new KitStatus.Claimed(lastClaim);
        }
        long every = this.every.toMillis();
        long readyAt = saturatedAdd(lastClaim, every);
        if (now >= readyAt) {
            return KitStatus.READY;
        }
        long left = readyAt - now;
        if (left > every) {
            // The clock went back since the claim: never make anyone wait longer than one cooldown.
            left = every;
            readyAt = saturatedAdd(now, every);
        }
        return new KitStatus.Waiting(Duration.ofMillis(left), readyAt);
    }

    /** {@code once}, or the cooldown written the way the config writes it ({@code 1d 12h}). */
    public String describe() {
        return this.every == null ? "once" : Durations.format(this.every);
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }
}
