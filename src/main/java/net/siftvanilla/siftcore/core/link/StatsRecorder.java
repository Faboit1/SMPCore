package net.siftvanilla.siftcore.core.link;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Records lifetime statistics. Implemented by the stats feature; thread-safe and cheap (memory, write-behind). */
public interface StatsRecorder {

    /** Counters stored per player. */
    enum Stat {
        KILLS, DEATHS, MOBS_KILLED, BLOCKS_MINED, MONEY_EARNED, PLAYTIME_SECONDS
    }

    StatsRecorder NONE = new StatsRecorder() {
        @Override
        public void add(UUID player, Stat stat, long amount) {
        }

        @Override
        public void kill(UUID killer, UUID victim) {
        }

        @Override
        public void death(UUID victim) {
        }

        @Override
        public long get(UUID player, Stat stat) {
            return 0;
        }

        @Override
        public int streak(UUID player) {
            return 0;
        }

        @Override
        public int bestStreak(UUID player) {
            return 0;
        }
    };

    void add(UUID player, Stat stat, long amount);

    /** A counted player kill: killer's kills and streak go up, victim's deaths go up and streak resets. */
    void kill(UUID killer, UUID victim);

    /** A death without kill credit: deaths go up and the streak resets. */
    void death(UUID victim);

    long get(UUID player, Stat stat);

    int streak(UUID player);

    int bestStreak(UUID player);

    /**
     * Brings these players' stats into memory (offline players are read from storage), so {@link #get} is exact for
     * them for a while. Team totals use it before adding up members who are offline. Never fails: a player whose
     * stats can't be read counts as 0.
     */
    default CompletableFuture<Void> preload(Collection<UUID> players) {
        return CompletableFuture.completedFuture(null);
    }
}
