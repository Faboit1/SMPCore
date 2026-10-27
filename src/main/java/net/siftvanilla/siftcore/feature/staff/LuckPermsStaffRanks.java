package net.siftvanilla.siftcore.feature.staff;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.entity.Player;

/**
 * Staff ranks from LuckPerms: the staff weight is the highest weight among the groups a player inherits (directly or
 * through other groups) that weigh at least the minimum; the bypass is their permission, checked by LuckPerms for
 * offline players too. Online players are answered from LuckPerms' loaded user; offline players are loaded from its
 * storage. Only loaded after checking that LuckPerms is enabled.
 */
final class LuckPermsStaffRanks implements StaffRanks {

    /** The plugin name LuckPerms registers under. */
    static final String PLUGIN = "LuckPerms";

    private final LuckPerms api;

    private LuckPermsStaffRanks(LuckPerms api) {
        this.api = api;
    }

    static StaffRanks connect() {
        return new LuckPermsStaffRanks(LuckPermsProvider.get());
    }

    @Override
    public Rank online(Player player, int minWeight) {
        User user = this.api.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return null;
        }
        return new Rank(player.hasPermission(StaffNodes.HIERARCHY_OWNER), weight(user, minWeight));
    }

    @Override
    public CompletableFuture<Rank> any(UUID player, int minWeight) {
        User loaded = this.api.getUserManager().getUser(player);
        if (loaded != null) {
            return CompletableFuture.completedFuture(rank(loaded, minWeight));
        }
        return this.api.getUserManager().loadUser(player).thenApply(user -> rank(user, minWeight));
    }

    private static Rank rank(User user, int minWeight) {
        QueryOptions options = user.getQueryOptions();
        boolean owner = user.getCachedData().getPermissionData(options).checkPermission(StaffNodes.HIERARCHY_OWNER).asBoolean();
        return new Rank(owner, weight(user, minWeight));
    }

    private static int weight(User user, int minWeight) {
        int best = 0;
        for (Group group : user.getInheritedGroups(user.getQueryOptions())) {
            int weight = group.getWeight().orElse(0);
            if (weight >= minWeight && weight > best) {
                best = weight;
            }
        }
        return best;
    }
}
