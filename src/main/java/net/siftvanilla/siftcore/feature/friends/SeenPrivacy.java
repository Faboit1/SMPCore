package net.siftvanilla.siftcore.feature.friends;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.Audience;
import org.bukkit.entity.Player;

/**
 * Who may see when a player was last online, for friend lists and profiles: the shared {@code seen-privacy} setting
 * ({@link SharedSettings#SEEN_PRIVACY}: everyone, friends or nobody). Staff who may look players up
 * ({@value #BYPASS}) always see it, and so does the player themselves. Being online is never hidden (it shows in the
 * tab list anyway); only the "seen 3d ago" time is.
 * <p>
 * Loaded players are answered from memory; offline players' rows are read in one query per screen, in the database
 * writer's order, and resolved with the server's lock and default ({@link StoredSetting}).
 */
final class SeenPrivacy {

    /** Staff who look players up with /whois see every last-seen time. */
    static final String BYPASS = "siftcore.staff.whois";

    private final PlayerSettings settings;
    private final FriendGraph graph;
    private final FriendStore store;

    SeenPrivacy(PlayerSettings settings, FriendGraph graph, FriendStore store) {
        this.settings = settings;
        this.graph = graph;
        this.store = store;
    }

    /**
     * Whether the owner's choice keeps their last-seen time from the viewer.
     *
     * @param audience who the owner shows it to
     * @param self     the viewer is the owner
     * @param friends  the viewer and the owner are friends
     * @param bypass   the viewer is staff who may look players up
     */
    static boolean hides(Audience audience, boolean self, boolean friends, boolean bypass) {
        if (self || bypass) {
            return false;
        }
        return !Relations.allows(audience, friends, false);
    }

    /**
     * The players among {@code players} whose last-seen time the viewer may not see. Call on the viewer's thread (the
     * permission check); the result may complete on the database thread.
     */
    CompletableFuture<Set<UUID>> hidden(Player viewer, Collection<UUID> players) {
        if (players.isEmpty() || viewer.hasPermission(BYPASS)) {
            return CompletableFuture.completedFuture(Set.of());
        }
        UUID self = viewer.getUniqueId();
        Set<UUID> hidden = new HashSet<>();
        List<UUID> offline = new ArrayList<>();
        for (UUID player : players) {
            if (player.equals(self)) {
                continue;
            }
            if (this.settings.loaded(player)) {
                if (hides(this.settings.get(player, SharedSettings.SEEN_PRIVACY), false, this.graph.friends(self, player), false)) {
                    hidden.add(player);
                }
            } else {
                offline.add(player);
            }
        }
        if (offline.isEmpty()) {
            return CompletableFuture.completedFuture(Set.copyOf(hidden));
        }
        StoredSetting<Audience> rule = StoredSetting.of(this.settings, SharedSettings.SEEN_PRIVACY);
        return this.store.write(this.store.settingRows(SharedSettings.SEEN_PRIVACY.id(), offline)).thenApply(rows -> {
            for (UUID player : offline) {
                if (hides(rule.resolve(rows.get(player)), false, this.graph.friends(self, player), false)) {
                    hidden.add(player);
                }
            }
            return Set.copyOf(hidden);
        });
    }
}
