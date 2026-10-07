package net.siftvanilla.siftcore.core.integration;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Plain-text rank labels (from LuckPerms when installed). Ranks are shown in white/gray only; colour codes in
 * prefixes are stripped so the design system holds.
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

    /** The label as a component in the secondary colour, or empty. */
    Component component(Player player);

    /** The primary group name (lowercase), {@code default} when unknown. */
    String group(UUID player);
}
