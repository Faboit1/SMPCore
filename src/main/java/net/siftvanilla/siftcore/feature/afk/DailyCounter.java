package net.siftvanilla.siftcore.feature.afk;

import java.time.LocalDate;

/**
 * Shards a player earned in the AFK zone today, for the optional daily limit. Starts unknown until the day's total
 * is read from the ledger, so a player can't earn past the limit while it loads. Rolls over at midnight. Pure logic;
 * thread-safe.
 */
final class DailyCounter {

    private LocalDate day;
    private long earned;
    private boolean loaded;

    /** Sets the total read from storage for {@code day}; rewards paid meanwhile are kept on top of it. */
    synchronized void loaded(LocalDate day, long stored) {
        if (this.day != null && this.day.equals(day) && this.loaded) {
            return;
        }
        long sinceLoad = this.day != null && this.day.equals(day) ? this.earned : 0;
        this.day = day;
        this.earned = Math.max(0, stored) + sinceLoad;
        this.loaded = true;
    }

    synchronized boolean isLoaded() {
        return this.loaded;
    }

    /** Shards earned on {@code today} (0 after midnight). */
    synchronized long earned(LocalDate today) {
        roll(today);
        return this.earned;
    }

    synchronized void add(LocalDate today, long amount) {
        roll(today);
        this.earned += amount;
    }

    private void roll(LocalDate today) {
        if (this.day == null || !this.day.equals(today)) {
            this.day = today;
            this.earned = 0;
        }
    }

    /** How much of {@code wanted} may still be paid under {@code cap} (0 or less = no limit). */
    static long allowance(long cap, long earnedToday, long wanted) {
        if (cap <= 0) {
            return wanted;
        }
        return Math.max(0, Math.min(wanted, cap - earnedToday));
    }
}
