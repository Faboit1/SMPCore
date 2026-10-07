package net.siftvanilla.siftcore.core.player;

import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

/**
 * Numeric limits granted by permission nodes such as {@code siftcore.homes.5}: the highest number among the
 * player's granted nodes with that prefix wins, otherwise {@code fallback}. {@code prefix.unlimited} means no limit.
 * Rank groups in LuckPerms carry these nodes.
 */
public final class Limits {

    public static final int UNLIMITED = Integer.MAX_VALUE;

    private Limits() {
    }

    public static int highest(Player player, String prefix, int fallback) {
        String base = prefix.endsWith(".") ? prefix : prefix + ".";
        if (player.hasPermission(base + "unlimited")) {
            return UNLIMITED;
        }
        int best = fallback;
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) {
                continue;
            }
            String node = info.getPermission();
            if (node.length() > base.length() && node.regionMatches(true, 0, base, 0, base.length())) {
                String suffix = node.substring(base.length());
                if (suffix.chars().allMatch(Character::isDigit) && suffix.length() < 10) {
                    best = Math.max(best, Integer.parseInt(suffix));
                }
            }
        }
        return best;
    }
}
