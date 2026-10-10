package net.siftvanilla.siftcore.feature.stats;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Where stats are kept. Every method is non-blocking; futures complete off the world threads. */
interface StatsStorage {

    /** A change to store for one player. */
    record PendingWrite(UUID player, StatsDelta delta) {
    }

    /** The stored stats of a player, {@link StatsSnapshot#ZERO} when they have none yet. */
    CompletableFuture<StatsSnapshot> load(UUID player);

    /**
     * Applies every change atomically to the stored values (as increments, so rows that were never loaded and
     * players who are offline are updated correctly). Each player's change is stored or refused on its own, so one
     * bad row cannot hold back the others. Completes with the players whose change the database refused (and why);
     * an empty map means everything was stored. Fails when nothing could be stored. Writes are applied in the order
     * this method is called.
     */
    CompletableFuture<Map<UUID, String>> write(List<PendingWrite> writes);

    /** The best {@code limit} rows of every board built from stats (see {@link Board#fromStats()}). */
    CompletableFuture<Map<Board, List<Leaderboard.Row>>> top(int limit, long kdrMinKills);
}
