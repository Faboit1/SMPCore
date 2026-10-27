package net.siftvanilla.siftcore.feature.spawners;

import java.util.UUID;
import net.siftvanilla.siftcore.core.link.TeamLookup;

/**
 * Who may use a spawner (stack it, open its storage, pick it up): its owner, members of the owner's team, and staff
 * with the bypass permission. Bukkit-free.
 */
enum Access {
    OWNER,
    TEAM,
    BYPASS,
    DENIED;

    boolean allowed() {
        return this != DENIED;
    }

    static Access of(UUID owner, UUID player, boolean bypass, TeamLookup teams) {
        if (owner.equals(player)) {
            return OWNER;
        }
        if (teams.sameTeam(owner, player)) {
            return TEAM;
        }
        return bypass ? BYPASS : DENIED;
    }
}
