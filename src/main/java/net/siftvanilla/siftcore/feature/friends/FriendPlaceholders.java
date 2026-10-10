package net.siftvanilla.siftcore.feature.friends;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.siftvanilla.siftcore.core.placeholder.Placeholders;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * The friends placeholders ({@code %siftcore_friends_*%}) for the scoreboard, the tab list and PlaceholderAPI. They
 * read memory only and answer {@code 0} for players who are not loaded. The online count may be asked for many times
 * a second (a scoreboard line per player), so it is cached per player for a second; vanished friends never count.
 */
final class FriendPlaceholders {

    private static final long ONLINE_CACHE_MILLIS = 1_000L;

    private record Cached(int value, long at) {
    }

    private final FriendService service;
    private final FriendGraph graph;
    private final Map<UUID, Cached> online = new ConcurrentHashMap<>();

    FriendPlaceholders(FriendService service) {
        this.service = service;
        this.graph = service.graph();
    }

    void register(Placeholders placeholders) {
        placeholders.register("friends_count", "Your number of friends (0 while not loaded)", player -> {
            FriendGraph.Node node = this.graph.loaded(player.getUniqueId());
            return node == null ? "0" : Integer.toString(node.friends().size());
        });
        placeholders.register("friends_online", "Your friends online now, vanished ones not counted", this::online);
        placeholders.register("friends_requests", "Friend requests waiting for you", player ->
            this.graph.isLoaded(player.getUniqueId()) ? Integer.toString(this.service.incoming(player.getUniqueId()).size()) : "0");
        placeholders.register("friends_limit", "Your friend limit (rank, default and hard cap)", player ->
            this.graph.isLoaded(player.getUniqueId()) ? Integer.toString(this.service.limit(player.getUniqueId())) : "0");
    }

    private String online(OfflinePlayer player) {
        UUID id = player.getUniqueId();
        FriendGraph.Node node = this.graph.loaded(id);
        if (node == null) {
            return "0";
        }
        long now = System.currentTimeMillis();
        Cached cached = this.online.get(id);
        if (cached != null && now - cached.at() < ONLINE_CACHE_MILLIS) {
            return Integer.toString(cached.value());
        }
        int count = 0;
        for (UUID friend : node.friends().keySet()) {
            if (Bukkit.getPlayer(friend) != null && !this.service.links().vanish().vanished(friend)) {
                count++;
            }
        }
        this.online.put(id, new Cached(count, now));
        return Integer.toString(count);
    }

    /** Drops cache entries of players who left. */
    void prune() {
        this.online.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    int cached() {
        return this.online.size();
    }
}
