package net.siftvanilla.siftcore.feature.stats;

import java.util.Locale;
import java.util.Optional;
import net.siftvanilla.siftcore.core.link.StatsRecorder;

/**
 * The additive counters of the {@code stats} table. Streak and best streak are not counters (a death resets the
 * streak), so they live in {@link StreakChange} instead.
 */
public enum Counter {
    KILLS("kills", "kills", Counter.INT_MAX),
    DEATHS("deaths", "deaths", Counter.INT_MAX),
    MOBS("mobs", "mobs_killed", Counter.BIG_MAX),
    BLOCKS("blocks", "blocks_mined", Counter.BIG_MAX),
    EARNED("earned", "money_earned", Counter.BIG_MAX),
    PLAYTIME("playtime", "playtime_seconds", Counter.BIG_MAX);

    /** The largest value of an {@code INTEGER} column (32 bits on MySQL): kills, deaths, streak and best streak. */
    public static final long INT_MAX = Integer.MAX_VALUE;
    /** The largest value kept in a {@code BIGINT} column, far enough below 2^63 that adding to it never overflows. */
    public static final long BIG_MAX = 1_000_000_000_000_000_000L;

    private final String id;
    private final String column;
    private final long max;

    Counter(String id, String column, long max) {
        this.id = id;
        this.column = column;
        this.max = max;
    }

    /** The largest value this counter holds; anything above is capped, in memory and in the database alike. */
    public long max() {
        return this.max;
    }

    /** Stable lowercase id used in commands and placeholders. */
    public String id() {
        return this.id;
    }

    /** The column in the {@code stats} table. */
    public String column() {
        return this.column;
    }

    /** The counter behind a {@link StatsRecorder.Stat}. */
    public static Counter of(StatsRecorder.Stat stat) {
        return switch (stat) {
            case KILLS -> KILLS;
            case DEATHS -> DEATHS;
            case MOBS_KILLED -> MOBS;
            case BLOCKS_MINED -> BLOCKS;
            case MONEY_EARNED -> EARNED;
            case PLAYTIME_SECONDS -> PLAYTIME;
        };
    }

    public static Optional<Counter> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String lower = id.toLowerCase(Locale.ROOT);
        for (Counter counter : values()) {
            if (counter.id.equals(lower)) {
                return Optional.of(counter);
            }
        }
        return Optional.empty();
    }
}
