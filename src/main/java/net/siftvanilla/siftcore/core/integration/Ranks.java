package net.siftvanilla.siftcore.core.integration;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Rank labels (from LuckPerms when installed). The label is always plain text: colour codes and tags in display names
 * and prefixes are stripped. A rank's colour comes from its own colour or gradient meta value instead
 * ({@link #component}).
 */
public interface Ranks {

    Ranks NONE = new Ranks() {
        @Override
        public String label(UUID player) {
            return "";
        }

        @Override
        public Component component(Player player) {
            return Component.empty();
        }

        @Override
        public String group(UUID player) {
            return "default";
        }
    };

    /** The rank label without formatting, empty for players without a rank label. */
    String label(UUID player);

    /** The label in the rank's colour or gradient (the secondary colour for ranks without one), or empty. */
    Component component(Player player);

    /** The primary group name (lowercase), {@code default} when unknown. */
    String group(UUID player);
}
