package net.siftvanilla.siftcore.core.player;

import java.util.Objects;
import net.siftvanilla.siftcore.core.text.MessageKey;

/**
 * A per-player on/off setting contributed by a feature, shown automatically in the settings dialog.
 *
 * @param id          stable id stored in the database, e.g. {@code scoreboard}
 * @param defaultOn   the value for players who never changed it
 * @param label       short label shown next to the switch
 * @param description one line explaining it
 * @param permission  permission needed to see it, or null
 */
public record Toggle(String id, boolean defaultOn, MessageKey label, MessageKey description, String permission) {

    public Toggle {
        Objects.requireNonNull(id);
        if (!id.matches("[a-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("Invalid toggle id " + id);
        }
    }
}
