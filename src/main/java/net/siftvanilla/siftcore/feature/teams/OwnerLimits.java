package net.siftvanilla.siftcore.feature.teams;

import java.util.UUID;
import net.siftvanilla.siftcore.core.player.Limits;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Keeps each team's member limit in line with its owner's rank ({@code siftcore.teams.size.<n>}). The limit is read
 * from the owner's permissions on their own thread whenever they are around (join, team actions, and once a
 * minute while online) and stored with the team, so it still applies while the owner is offline.
 */
final class OwnerLimits implements Listener {

    static final String PREFIX = "siftcore.teams.size";

    private final TeamRegistry registry;
    private final TeamService service;
    private final Scheduler scheduler;

    OwnerLimits(TeamRegistry registry, TeamService service, Scheduler scheduler) {
        this.registry = registry;
        this.service = service;
        this.scheduler = scheduler;
    }

    /** Reads the owner's rank limit and stores it if it changed. Call on the player's thread. */
    void refresh(Player player) {
        UUID id = player.getUniqueId();
        Team team = this.registry.of(id).orElse(null);
        if (team == null || !team.owner().equals(id)) {
            return;
        }
        this.service.updateRankLimit(id, Limits.highest(player, PREFIX, 0));
    }

    /** Schedules a refresh on the thread of every online owner. Safe from any thread. */
    void refreshOnlineOwners() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Team team = this.registry.of(player.getUniqueId()).orElse(null);
            if (team != null && team.owner().equals(player.getUniqueId())) {
                this.scheduler.entity(player, () -> refresh(player), null);
            }
        }
    }

    /** Refreshes on the player's thread later (for example right after they became an owner). */
    void refreshLater(UUID player) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            this.scheduler.entity(online, () -> refresh(online), null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }
}
