package net.siftvanilla.siftcore.api;

import java.util.UUID;

/** Read-only rank information. Without LuckPerms every player has no label and the group {@code default}. */
public interface RankView {

    /** The rank label as plain text (no colours), empty for players without one. Known for online players. */
    String label(UUID player);

    /** The primary group name in lowercase; {@code default} when unknown or offline. */
    String group(UUID player);
}
