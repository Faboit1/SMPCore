package net.siftvanilla.siftcore.core.player;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.command.Cooldowns;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Loads per-player data before a player enters the world (the async pre-login event may block on storage) and
 * forgets it when they leave.
 */
public final class PlayerLifecycle implements Listener {

    private final PlayerDirectory directory;
    private final PlayerSettings settings;
    private final Cooldowns cooldowns;
    private final Logger logger;

    public PlayerLifecycle(PlayerDirectory directory, PlayerSettings settings, Cooldowns cooldowns, Logger logger) {
        this.directory = directory;
        this.settings = settings;
        this.cooldowns = cooldowns;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            this.settings.load(event.getUniqueId()).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            this.logger.log(Level.WARNING, "Could not load settings of " + event.getName() + "; defaults are used", e);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        InetSocketAddress address = player.getAddress();
        String ip = address == null || address.getAddress() == null ? null : address.getAddress().getHostAddress();
        this.directory.recordJoin(player.getUniqueId(), player.getName(), ip);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        this.directory.recordQuit(player.getUniqueId());
        this.settings.forget(player.getUniqueId());
        this.cooldowns.forget(player.getUniqueId());
    }
}
