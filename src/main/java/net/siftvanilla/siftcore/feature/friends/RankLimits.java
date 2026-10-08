package net.siftvanilla.siftcore.feature.friends;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.integration.Ranks;
import net.siftvanilla.siftcore.core.player.Limits;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Keeps each player's friend limit in line with their rank ({@code siftcore.friends.limit.<n>}). The limit is read
 * from the player's permissions on their own thread when they join and once a minute while they are online, kept in
 * memory, and stored with their rank label in {@code friend_profiles} whenever either changed, so a request or an
 * accept checked while they are offline uses their real limit. A lower limit never removes friends; it only stops new
 * ones.
 */
final class RankLimits {

    static final String PREFIX = "siftcore.friends.limit";

    /** What is stored for a player: their rank limit and label. */
    private record Stamp(int limit, String label) {
    }

    private final FriendGraph graph;
    private final FriendStore store;
    private final Ranks ranks;
    private final Scheduler scheduler;
    private final Logger logger;
    private final Map<UUID, Stamp> stored = new ConcurrentHashMap<>();

    RankLimits(FriendGraph graph, FriendStore store, Ranks ranks, Scheduler scheduler, Logger logger) {
        this.graph = graph;
        this.store = store;
        this.ranks = ranks;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    /** What the load found in storage ({@code storedLimit} -1 when there is no row). */
    void seed(UUID player, int storedLimit, String storedLabel) {
        if (storedLimit >= 0) {
            this.stored.put(player, new Stamp(storedLimit, storedLabel));
        } else {
            this.stored.remove(player);
        }
    }

    /** Reads the player's rank limit and label and stores them if they changed. Call on the player's thread. */
    void refresh(Player player) {
        UUID id = player.getUniqueId();
        if (!this.graph.isLoaded(id)) {
            return;
        }
        int limit = Limits.highest(player, PREFIX, 0);
        String label = this.ranks.label(id);
        String cleanLabel = label == null || label.isBlank() ? null : NoteText.cut(label.strip(), FriendStore.RANK_LABEL_LENGTH);
        this.graph.rankLimit(id, limit);
        Stamp now = new Stamp(limit, cleanLabel);
        Stamp before = this.stored.get(id);
        if (before == null && limit == 0 && cleanLabel == null) {
            return;
        }
        if (before == null || before.limit() != limit || !Objects.equals(before.label(), cleanLabel)) {
            this.stored.put(id, now);
            this.store.write(this.store.saveProfile(id, limit, cleanLabel)).exceptionally(error -> {
                this.stored.remove(id, now);
                this.logger.log(Level.WARNING, "Could not store the friend limit of " + player.getName(), error);
                return null;
            });
        }
    }

    /** Schedules a refresh on the thread of every online player. Safe from any thread. */
    void refreshOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.scheduler.entity(player, () -> refresh(player), null);
        }
    }

    void forget(UUID player) {
        this.stored.remove(player);
    }

    void clear() {
        this.stored.clear();
    }
}
