package net.siftvanilla.siftcore.feature.displays;

import java.util.Objects;

/**
 * A row of the {@code displays} table: a position set in-game.
 *
 * @param id       the display name
 * @param template the template of a display created in-game, or null for a display from displays.yml that was
 *                 moved in-game (its template keeps coming from the file)
 * @param position where it stands
 * @param placedBy the UUID of the admin who placed it, or {@code console}
 * @param placedAt epoch millis
 */
public record Placement(String id, String template, DisplayPosition position, String placedBy, long placedAt) {

    public Placement {
        Objects.requireNonNull(id);
        Objects.requireNonNull(position);
    }
}
