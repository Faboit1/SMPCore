package net.siftvanilla.siftcore.feature.cosmetics;

import net.siftvanilla.siftcore.core.Services;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Keeps nickname holds current: a player who joins or leaves able to show their nickname renews its hold
 * ({@code nicknames.hold}; the sweeper renews it hourly while they play), and a player whose nickname another player
 * took over while they couldn't use it is told once, a moment after joining. Runs on the player's thread.
 */
final class CosmeticsListener implements Listener {

    /** Ticks after joining before the lost-nickname notice, so it isn't buried in the join messages. */
    private static final long NOTICE_DELAY_TICKS = 60L;

    private final Services services;
    private final CosmeticsService cosmetics;

    CosmeticsListener(Services services, CosmeticsService cosmetics) {
        this.services = services;
        this.cosmetics = cosmetics;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        this.cosmetics.seen(player, true);
        if (this.cosmetics.profiles().get(player.getUniqueId()).nickLost() != null) {
            this.services.scheduler().entityLater(player, () -> this.cosmetics.tellLostNick(player), null, NOTICE_DELAY_TICKS);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.cosmetics.seen(event.getPlayer(), true);
    }
}
