package net.siftvanilla.siftcore.feature.staff;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Where the staff hierarchy learns how a player ranks: whether they hold {@link StaffNodes#HIERARCHY_OWNER} (the
 * owner) and their staff weight. {@link LuckPermsStaffRanks} reads LuckPerms group weights (also for offline players);
 * {@link #PERMISSIONS} is used without LuckPerms and knows only the owner node of online players.
 */
interface StaffRanks {

    /**
     * How a player ranks.
     *
     * @param owner  holds {@link StaffNodes#HIERARCHY_OWNER}
     * @param weight the staff weight: the highest weight of their staff groups, 0 when they aren't staff
     */
    record Rank(boolean owner, int weight) {

        static final Rank NONE = new Rank(false, 0);
    }

    /** Without a permissions plugin that has weights: only the owner node of online players is known. */
    StaffRanks PERMISSIONS = new StaffRanks() {
        @Override
        public Rank online(Player player, int minWeight) {
            return new Rank(player.hasPermission(StaffNodes.HIERARCHY_OWNER), 0);
        }

        @Override
        public CompletableFuture<Rank> any(UUID player, int minWeight) {
            Player online = Bukkit.getPlayer(player);
            return CompletableFuture.completedFuture(online == null ? Rank.NONE : online(online, minWeight));
        }
    };

    /**
     * An online player's rank, right away (from memory); null when it can't be known right now. Any thread.
     *
     * @param minWeight groups lighter than this are not staff groups
     */
    Rank online(Player player, int minWeight);

    /**
     * Any player's rank, loaded when they are offline. Completes exceptionally when it can't be found out, in which
     * case nothing is done to the player. Any thread; may complete on another thread.
     */
    CompletableFuture<Rank> any(UUID player, int minWeight);
}
