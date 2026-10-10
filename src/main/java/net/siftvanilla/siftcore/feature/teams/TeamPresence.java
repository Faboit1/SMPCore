package net.siftvanilla.siftcore.feature.teams;

import java.util.UUID;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import org.bukkit.Bukkit;

/**
 * Who of a team counts as online: connected and not vanished. A vanished member shows as offline to everyone else,
 * in member lists, online counts and placeholders, but still sees themselves as online. Thread-safe (online lookups
 * and the vanish contract are both safe from any thread).
 */
final class TeamPresence {

    private final VanishStatus vanish;

    TeamPresence(VanishStatus vanish) {
        this.vanish = vanish;
    }

    /** Whether {@code player} is vanished (never shown as online, never offered as a target). */
    boolean hidden(UUID player) {
        return this.vanish.vanished(player);
    }

    /** Whether {@code member} shows as online to {@code viewer} (null for nobody in particular, e.g. the console). */
    boolean online(UUID member, UUID viewer) {
        return Bukkit.getPlayer(member) != null && (member.equals(viewer) || !this.vanish.vanished(member));
    }

    /** Members of the team who show as online to {@code viewer} (null for nobody in particular). */
    int online(Team team, UUID viewer) {
        int count = 0;
        for (UUID member : team.memberIds()) {
            if (online(member, viewer)) {
                count++;
            }
        }
        return count;
    }
}
