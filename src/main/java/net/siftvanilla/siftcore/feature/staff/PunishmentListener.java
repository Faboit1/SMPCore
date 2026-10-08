package net.siftvanilla.siftcore.feature.staff;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/** Keeps banned players out and shows players the warnings they got while they were offline. */
final class PunishmentListener implements Listener {

    private final Punishments punishments;
    private final StaffStore store;
    private final Scheduler scheduler;
    private final Messenger messenger;
    private final StaffText text;
    private final Logger logger;

    PunishmentListener(Punishments punishments, StaffStore store, Scheduler scheduler, Messenger messenger, StaffText text,
                       Logger logger) {
        this.punishments = punishments;
        this.store = store;
        this.scheduler = scheduler;
        this.messenger = messenger;
        this.text = text;
        this.logger = logger;
    }

    /** Runs on the login (authenticator) thread and reads only the in-memory bans, so it never blocks on storage. */
    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        this.punishments.activeBan(event.getUniqueId())
            .ifPresent(ban -> event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, this.punishments.banScreen(ban)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        var ban = this.punishments.activeBan(player.getUniqueId());
        if (ban.isPresent()) {
            // Banned between the login check and the join.
            this.punishments.removeBanned(player, ban.get());
            return;
        }
        this.store.unseenWarnings(player.getUniqueId()).whenComplete((warnings, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "Could not load the warnings of " + player.getName(), error);
                return;
            }
            if (warnings.isEmpty()) {
                return;
            }
            this.scheduler.entity(player, () -> {
                long now = System.currentTimeMillis();
                List<Long> shown = new ArrayList<>(warnings.size());
                for (Punishment warning : warnings) {
                    this.messenger.send(player, StaffMessages.WARN_MISSED, Arg.time("ago", StaffText.since(warning.created(), now)),
                        Arg.text("reason", this.text.reason(warning.reason())));
                    shown.add(warning.id());
                }
                this.store.markNotified(shown).whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        this.logger.log(Level.WARNING, "Could not mark the warnings of " + player.getName() + " as seen", failure);
                    }
                });
            }, null);
        });
    }
}
