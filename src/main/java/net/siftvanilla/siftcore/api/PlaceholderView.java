package net.siftvanilla.siftcore.api;

import java.util.Map;
import java.util.Optional;
import org.bukkit.OfflinePlayer;

/**
 * Read-only access to SiftCore's placeholders without PlaceholderAPI. Names are without the {@code siftcore_}
 * prefix, e.g. {@code balance} or {@code baltop_name_1}.
 */
public interface PlaceholderView {

    /**
     * The value of a placeholder for a player, or empty when the name is unknown. Placeholders that describe the
     * server rather than a player (leaderboard places) also accept a null player.
     */
    Optional<String> resolve(OfflinePlayer player, String name);

    /** Replaces every {@code %siftcore_<name>%} in {@code text}; unknown names are left as they are. */
    String apply(OfflinePlayer player, String text);

    /** Every placeholder name (or usage, for prefixed ones like {@code baltop_name_<rank>}) and what it shows. */
    Map<String, String> available();
}
