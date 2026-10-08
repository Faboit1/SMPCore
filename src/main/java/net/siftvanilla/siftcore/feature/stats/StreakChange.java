package net.siftvanilla.siftcore.feature.stats;

/**
 * What a run of kills, deaths and resets did to a player's streak and best streak, expressed without knowing the
 * values they had before. That lets the write-behind keep events in memory and later apply them to the database as
 * one atomic update, also for players whose row was never loaded:
 * <pre>
 *   streak' = (keepStreak ? streak : 0) + addStreak
 *   best'   = max(keepBest ? best : 0, carry ? streak + carryAdd : 0, peak)
 * </pre>
 * {@code carry} covers a run that continues the old streak (its best is the old streak plus the kills on top),
 * {@code peak} the best run that started after a reset. {@link #then} composes two changes exactly, and composition
 * is associative, so splitting a sequence of events over any number of flushes gives the same result as applying
 * the events one by one.
 */
public record StreakChange(boolean keepStreak, long addStreak, boolean keepBest, boolean carry, long carryAdd, long peak) {

    /** No change. */
    public static final StreakChange NONE = new StreakChange(true, 0, true, true, 0, 0);
    /** A counted kill: the streak goes up by one. */
    public static final StreakChange KILL = new StreakChange(true, 1, true, true, 1, 0);
    /** A death: the streak ends (its length already counts toward the best). */
    public static final StreakChange DEATH = new StreakChange(false, 0, true, true, 0, 0);
    /** An admin reset: streak and best streak start over from zero. */
    public static final StreakChange RESET = new StreakChange(false, 0, false, false, 0, 0);

    public StreakChange {
        if (addStreak < 0 || carryAdd < 0 || peak < 0) {
            throw new IllegalArgumentException("Streak changes cannot be negative");
        }
        if (!carry) {
            carryAdd = 0;
        }
    }

    /** This change followed by {@code next}. */
    public StreakChange then(StreakChange next) {
        boolean keep = this.keepStreak && next.keepStreak;
        long add = next.keepStreak ? plus(this.addStreak, next.addStreak) : next.addStreak;
        boolean best = this.keepBest && next.keepBest;
        boolean carried = false;
        long carriedAdd = 0;
        if (next.keepBest && this.carry) {
            // The best of this change still counts after next, including its run on top of the old streak.
            carried = true;
            carriedAdd = this.carryAdd;
        }
        if (next.carry && this.keepStreak) {
            // next's run continues a streak that still contains the old one.
            long candidate = plus(this.addStreak, next.carryAdd);
            carriedAdd = carried ? Math.max(carriedAdd, candidate) : candidate;
            carried = true;
        }
        long top = next.peak;
        if (next.keepBest) {
            top = Math.max(top, this.peak);
        }
        if (next.carry && !this.keepStreak) {
            // next's run continues a streak that started after a reset inside this change.
            top = Math.max(top, plus(this.addStreak, next.carryAdd));
        }
        return new StreakChange(keep, add, best, carried, carriedAdd, top);
    }

    /** The streak after this change, given the streak before. */
    public long streak(long before) {
        return plus(this.keepStreak ? before : 0, this.addStreak);
    }

    /** The best streak after this change, given the streak and best streak before. */
    public long best(long streakBefore, long bestBefore) {
        long result = this.peak;
        if (this.keepBest) {
            result = Math.max(result, bestBefore);
        }
        if (this.carry) {
            result = Math.max(result, plus(streakBefore, this.carryAdd));
        }
        return result;
    }

    public boolean isNone() {
        return equals(NONE);
    }

    static long plus(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }
}
