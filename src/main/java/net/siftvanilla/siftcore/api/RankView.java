package net.siftvanilla.siftcore.api;

import java.util.UUID;

/**
 * Read-only rank information. Without LuckPerms every player has no label and the group {@code default}.
 * <p>
 * Players with {@code siftcore.settings.hide-rank} can turn "Show my rank" off: their {@link #label} is then empty, as
 * everywhere SiftCore shows ranks. {@link #group} stays their real group, because plugins act on it (perks, limits);
 * to show a rank, use the label (or the {@code %siftcore_rank_group%} placeholder, which reads {@code default} for
 * them).
 */
public interface RankView {

    /**
     * The rank label as plain text (no colours), empty for players without one or who turned "Show my rank" off.
     * Known for online players.
     */
    String label(UUID player);

    /**
     * The primary group name in lowercase; {@code default} when unknown or offline. The real group also for players who
     * hide their rank: don't display it as their rank.
     */
    String group(UUID player);
}
