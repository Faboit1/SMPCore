package net.siftvanilla.siftcore.api;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Read-only view of combat tags. A tagged player can't teleport, open most menus or log out safely. */
public interface CombatView {

    /** Whether the player is in combat right now. */
    boolean tagged(UUID player);

    /** Time left in combat, {@link Duration#ZERO} when not tagged. */
    Duration remaining(UUID player);

    /** Who hit the player last, while they are tagged (that player gets the kill if they log out). */
    Optional<UUID> lastAttacker(UUID player);
}
