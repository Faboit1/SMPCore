package net.siftvanilla.siftcore.feature.staff;

import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Who did a staff action: a player, or the console (any non-player sender).
 *
 * @param id   a player UUID as text, or {@code console}; stored with punishments and in the audit log
 * @param name the player's name, or {@code Console}
 * @param uuid the player's UUID, null for the console
 */
record Actor(String id, String name, UUID uuid) {

    static final String CONSOLE_ID = "console";
    static final String CONSOLE_NAME = "Console";

    static Actor of(CommandSender sender) {
        if (sender instanceof Player player) {
            return new Actor(player.getUniqueId().toString(), player.getName(), player.getUniqueId());
        }
        return console();
    }

    static Actor console() {
        return new Actor(CONSOLE_ID, CONSOLE_NAME, null);
    }

    /** The actor who stored a record, rebuilt from its stored id and name. */
    static Actor stored(String id, String name) {
        if (id == null || id.isEmpty() || id.equals(CONSOLE_ID)) {
            return console();
        }
        try {
            return new Actor(id, name, UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return new Actor(id, name, null);
        }
    }

    boolean isConsole() {
        return this.uuid == null;
    }

    boolean is(UUID player) {
        return this.uuid != null && this.uuid.equals(player);
    }
}
