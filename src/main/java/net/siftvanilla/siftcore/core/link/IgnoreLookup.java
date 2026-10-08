package net.siftvanilla.siftcore.core.link;

import java.util.UUID;

/**
 * Ignore lists. Implemented by the chat feature; the friends feature consults it so an ignored player cannot send
 * friend requests. Thread-safe and cheap.
 */
public interface IgnoreLookup {

    IgnoreLookup NONE = (player, other) -> false;

    /** True when {@code player} ignores {@code other}. */
    boolean ignores(UUID player, UUID other);
}
