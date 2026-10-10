package net.siftvanilla.siftcore.feature.teams;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.Audience;
import org.bukkit.entity.Player;

/**
 * Who may see when a team member was last online, for the member lists of {@code /team} and {@code /team info}: the
 * shared {@code seen-privacy} setting ({@link SharedSettings#SEEN_PRIVACY}: everyone, friends or nobody), the same rule
 * as the friends list and {@code /seen}. Being in the same team does not count; staff who may look players up
 * ({@value #BYPASS}) and the member themselves always see it. Being online is never hidden; only the "seen 3d ago"
 * time is.
 * <p>
 * Loaded players are answered from memory; offline members' rows are read in one query, in the database writer's
 * order (so a change queued just before is seen), and resolved with the server's lock, hidden list and default.
 */
final class TeamSeen {

    /** Staff who look players up with /whois see every last-seen time. */
    static final String BYPASS = "siftcore.staff.whois";

    private final PlayerSettings settings;
    private final Relations relations;
    private final TeamStore store;

    TeamSeen(PlayerSettings settings, Relations relations, TeamStore store) {
        this.settings = settings;
        this.relations = relations;
        this.store = store;
    }

    /**
     * Whether the member's choice lets the viewer see their last-seen time.
     *
     * @param audience who the member shows it to
     * @param self     the viewer is the member
     * @param friends  the viewer and the member are friends
     * @param bypass   the viewer is staff who may look players up
     */
    static boolean shows(Audience audience, boolean self, boolean friends, boolean bypass) {
        return self || bypass || Relations.allows(audience, friends, false);
    }

    /**
     * The value a stored {@code seen-privacy} row (null for none) reads as, the way {@link PlayerSettings} resolves it:
     * a value the server forces (its lock, or the default of a setting it hides) wins, then a readable stored value,
     * then the server's default.
     */
    static Audience resolve(String stored, Audience forced, Audience fallback) {
        if (forced != null) {
            return forced;
        }
        Audience value = stored == null ? null : SharedSettings.SEEN_PRIVACY.decodeOrNull(stored);
        return value != null ? value : fallback;
    }

    /**
     * The players among {@code members} whose last-seen time the viewer may see. Call on the viewer's thread (the
     * permission check); the result may complete on the database thread.
     */
    CompletableFuture<Set<UUID>> visible(Player viewer, Collection<UUID> members) {
        if (members.isEmpty()) {
            return CompletableFuture.completedFuture(Set.of());
        }
        if (viewer.hasPermission(BYPASS)) {
            return CompletableFuture.completedFuture(Set.copyOf(members));
        }
        UUID self = viewer.getUniqueId();
        Set<UUID> shown = new HashSet<>();
        List<UUID> offline = new ArrayList<>();
        for (UUID member : members) {
            if (member.equals(self)) {
                shown.add(member);
            } else if (this.settings.loaded(member)) {
                if (shows(this.settings.get(member, SharedSettings.SEEN_PRIVACY), false, this.relations.areFriends(self, member), false)) {
                    shown.add(member);
                }
            } else {
                offline.add(member);
            }
        }
        if (offline.isEmpty()) {
            return CompletableFuture.completedFuture(Set.copyOf(shown));
        }
        // The rule in force now (a /sift reload may change the server's default or lock later).
        Audience fallback = this.settings.defaultValue(SharedSettings.SEEN_PRIVACY);
        Audience forced = this.settings.locked(SharedSettings.SEEN_PRIVACY) || this.settings.hidden(SharedSettings.SEEN_PRIVACY)
            ? fallback : null;
        return this.store.settingRows(SharedSettings.SEEN_PRIVACY.id(), offline).thenApply((Map<UUID, String> rows) -> {
            for (UUID member : offline) {
                if (shows(resolve(rows.get(member), forced, fallback), false, this.relations.areFriends(self, member), false)) {
                    shown.add(member);
                }
            }
            return Set.copyOf(shown);
        });
    }
}
