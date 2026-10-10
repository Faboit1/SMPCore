package net.siftvanilla.siftcore.feature.combat;

import java.util.UUID;
import net.siftvanilla.siftcore.core.link.VanishStatus;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/**
 * Who takes part in combat, and who may see that someone did. Players in creative or spectator mode and vanished
 * staff are out of play: their hits tag nobody and give no kill credit, and hits on them tag nobody. A vanished
 * player's name never reaches players who can't see them, so death messages and announcements about them only go
 * to the people involved and to staff who see vanished players.
 */
final class Participants {

    private final VanishStatus vanish;

    Participants(VanishStatus vanish) {
        this.vanish = vanish;
    }

    /** True when the player takes no part in combat. Safe from any thread (game mode is a plain read). */
    boolean outOfPlay(Player player) {
        GameMode mode = player.getGameMode();
        return mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR || this.vanish.vanished(player.getUniqueId());
    }

    /**
     * True when {@code viewer} may read a line naming {@code subject} (a player id, or null for nobody): the
     * subject is not vanished, or is the viewer, or is online and visible to the viewer.
     */
    boolean visibleTo(Player viewer, UUID subject) {
        if (subject == null || viewer.getUniqueId().equals(subject) || !this.vanish.vanished(subject)) {
            return true;
        }
        Player online = Bukkit.getPlayer(subject);
        return online != null && viewer.canSee(online);
    }

    /** True when the player is vanished (their name is kept from players who can't see them). */
    boolean hidden(UUID player) {
        return player != null && this.vanish.vanished(player);
    }
}
