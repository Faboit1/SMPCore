package net.siftvanilla.siftcore.core.link;

import java.util.UUID;

/**
 * Whether a staff member is vanished. Implemented by the staff feature; join/quit messages, online counts and
 * "last seen" lookups consult it so a vanished player stays invisible everywhere. Thread-safe and cheap.
 */
public interface VanishStatus {

    VanishStatus NONE = player -> false;

    boolean vanished(UUID player);
}
