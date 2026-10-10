package net.siftvanilla.siftcore.feature.stats;

/**
 * A player's stats at one moment. Immutable.
 *
 * @param kills      counted player kills
 * @param deaths     deaths (with or without a killer)
 * @param streak     kills since the last death
 * @param bestStreak the longest streak ever
 * @param mobs       mobs killed
 * @param blocks     blocks mined
 * @param earned     money earned (see {@link Earnings})
 * @param playtime   active (not AFK) time played, in seconds
 */
public record StatsSnapshot(long kills, long deaths, long streak, long bestStreak, long mobs, long blocks, long earned,
                            long playtime) {

    public static final StatsSnapshot ZERO = new StatsSnapshot(0, 0, 0, 0, 0, 0, 0, 0);

    public StatsSnapshot {
        if (kills < 0 || deaths < 0 || streak < 0 || bestStreak < 0 || mobs < 0 || blocks < 0 || earned < 0 || playtime < 0) {
            throw new IllegalArgumentException("Stats cannot be negative");
        }
    }

    public long get(Counter counter) {
        return switch (counter) {
            case KILLS -> this.kills;
            case DEATHS -> this.deaths;
            case MOBS -> this.mobs;
            case BLOCKS -> this.blocks;
            case EARNED -> this.earned;
            case PLAYTIME -> this.playtime;
        };
    }

    /** Kills per death, with at least one death counted (see {@link Kdr}). */
    public String kdr() {
        return Kdr.format(this.kills, this.deaths);
    }
}
