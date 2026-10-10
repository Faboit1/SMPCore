package net.siftvanilla.siftcore.ui.hub;

import java.util.function.Consumer;
import net.siftvanilla.siftcore.core.text.MessageKey;
import org.bukkit.entity.Player;

/**
 * A button in the main menu, contributed by a feature.
 *
 * @param id          stable id, also the pause-menu route {@code siftcore:hub/<id>}
 * @param order       position (lower first)
 * @param label       button label
 * @param description tooltip
 * @param permission  needed to see it, or null
 * @param open        opens the feature for the player (runs on the player's thread)
 */
public record HubEntry(String id, int order, MessageKey label, MessageKey description, String permission,
                       Consumer<Player> open) {
}
