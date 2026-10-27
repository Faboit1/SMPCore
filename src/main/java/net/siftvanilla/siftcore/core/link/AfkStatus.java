package net.siftvanilla.siftcore.core.link;

import java.util.UUID;

/** Whether a player is AFK. Implemented by the AFK feature; thread-safe. */
public interface AfkStatus {

    AfkStatus NONE = player -> false;

    boolean afk(UUID player);
}
