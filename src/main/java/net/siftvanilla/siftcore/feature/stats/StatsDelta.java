package net.siftvanilla.siftcore.feature.stats;

import java.util.Arrays;
import java.util.StringJoiner;

/**
 * Changes to one player's stats that are not stored yet, independent of the values they apply to. Each counter
 * becomes {@code (keep ? old : 0) + add}, and the streak follows a {@link StreakChange}. So a delta can be written
 * as one atomic {@code UPDATE ... SET kills = kills * ? + ?} without reading the row first, which is what makes
 * write-behind safe for players who are offline or whose row was never loaded.
 * <p>
 * Deltas are immutable. {@link #then} composes two deltas exactly; applying the composition equals applying both in
 * order, which the write-behind relies on when a failed write is merged back in front of newer changes.
 */
public final class StatsDelta {

    private static final Counter[] COUNTERS = Counter.values();
    private static final boolean[] KEEP_ALL = keepAll();
    private static final long[] ADD_NONE = new long[COUNTERS.length];

    /** No change. */
    public static final StatsDelta NONE = new StatsDelta(KEEP_ALL, ADD_NONE, StreakChange.NONE);

    private final boolean[] keep;
    private final long[] add;
    private final StreakChange streak;

    private StatsDelta(boolean[] keep, long[] add, StreakChange streak) {
        this.keep = keep;
        this.add = add;
        this.streak = streak;
    }

    private static boolean[] keepAll() {
        boolean[] keep = new boolean[COUNTERS.length];
        Arrays.fill(keep, true);
        return keep;
    }

    /** Adds a positive amount to a counter. */
    public static StatsDelta add(Counter counter, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive, got " + amount);
        }
        long[] add = ADD_NONE.clone();
        add[counter.ordinal()] = amount;
        return new StatsDelta(KEEP_ALL, add, StreakChange.NONE);
    }

    /** Sets a counter to a value (staff correction). */
    public static StatsDelta set(Counter counter, long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Value cannot be negative, got " + value);
        }
        boolean[] keep = KEEP_ALL.clone();
        keep[counter.ordinal()] = false;
        long[] add = ADD_NONE.clone();
        add[counter.ordinal()] = value;
        return new StatsDelta(keep, add, StreakChange.NONE);
    }

    /** A counted player kill: one more kill and one more on the streak. */
    public static StatsDelta kill() {
        long[] add = ADD_NONE.clone();
        add[Counter.KILLS.ordinal()] = 1;
        return new StatsDelta(KEEP_ALL, add, StreakChange.KILL);
    }

    /** A death: one more death and the streak ends. */
    public static StatsDelta death() {
        long[] add = ADD_NONE.clone();
        add[Counter.DEATHS.ordinal()] = 1;
        return new StatsDelta(KEEP_ALL, add, StreakChange.DEATH);
    }

    /** Every stat back to zero (staff reset). */
    public static StatsDelta reset() {
        return new StatsDelta(new boolean[COUNTERS.length], ADD_NONE, StreakChange.RESET);
    }

    /** This delta followed by {@code next}. */
    public StatsDelta then(StatsDelta next) {
        if (next.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return next;
        }
        boolean[] keep = new boolean[COUNTERS.length];
        long[] add = new long[COUNTERS.length];
        for (int i = 0; i < COUNTERS.length; i++) {
            keep[i] = this.keep[i] && next.keep[i];
            add[i] = next.keep[i] ? StreakChange.plus(this.add[i], next.add[i]) : next.add[i];
        }
        return new StatsDelta(keep, add, this.streak.then(next.streak));
    }

    /**
     * The stats after this delta. Every value is capped at its column's maximum ({@link Counter#max()}, and
     * {@link Counter#INT_MAX} for the streaks), exactly like the database write does. Capping commutes with this
     * algebra because nothing ever subtracts: applying deltas one by one or composed gives the same capped result.
     */
    public StatsSnapshot applyTo(StatsSnapshot before) {
        if (isEmpty()) {
            return before;
        }
        return new StatsSnapshot(
            counter(Counter.KILLS, before.kills()),
            counter(Counter.DEATHS, before.deaths()),
            Math.min(Counter.INT_MAX, this.streak.streak(before.streak())),
            Math.min(Counter.INT_MAX, this.streak.best(before.streak(), before.bestStreak())),
            counter(Counter.MOBS, before.mobs()),
            counter(Counter.BLOCKS, before.blocks()),
            counter(Counter.EARNED, before.earned()),
            counter(Counter.PLAYTIME, before.playtime()));
    }

    private long counter(Counter counter, long before) {
        int i = counter.ordinal();
        return Math.min(counter.max(), StreakChange.plus(this.keep[i] ? before : 0, this.add[i]));
    }

    /** Whether the counter keeps its old value (false after a staff set or reset). */
    public boolean keeps(Counter counter) {
        return this.keep[counter.ordinal()];
    }

    /** What is added to the counter (after a reset, the new value). */
    public long added(Counter counter) {
        return this.add[counter.ordinal()];
    }

    public StreakChange streak() {
        return this.streak;
    }

    public boolean isEmpty() {
        return this == NONE || (Arrays.equals(this.keep, KEEP_ALL) && Arrays.equals(this.add, ADD_NONE) && this.streak.isNone());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StatsDelta delta && Arrays.equals(this.keep, delta.keep) && Arrays.equals(this.add, delta.add)
            && this.streak.equals(delta.streak);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Arrays.hashCode(this.keep) + Arrays.hashCode(this.add)) + this.streak.hashCode();
    }

    @Override
    public String toString() {
        StringJoiner joiner = new StringJoiner(", ", "StatsDelta[", "]");
        for (Counter counter : COUNTERS) {
            int i = counter.ordinal();
            if (!this.keep[i] || this.add[i] != 0) {
                joiner.add(counter.id() + (this.keep[i] ? "+" : "=") + this.add[i]);
            }
        }
        if (!this.streak.isNone()) {
            joiner.add(this.streak.toString());
        }
        return joiner.toString();
    }
}
