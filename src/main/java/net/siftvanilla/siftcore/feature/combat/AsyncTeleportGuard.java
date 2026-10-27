package net.siftvanilla.siftcore.feature.combat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.BiPredicate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

/**
 * Stops ender pearls and chorus fruit from carrying a player somewhere they may not go. On Canvas those teleports
 * fire no {@link PlayerTeleportEvent}; Canvas fires its own {@code EntityTeleportAsyncEvent} instead, on the region
 * thread. It is registered by name, so the jar keeps working (without this guard) on servers that lack it.
 */
final class AsyncTeleportGuard {

    private static final String EVENT = "io.canvasmc.canvas.event.EntityTeleportAsyncEvent";

    private AsyncTeleportGuard() {
    }

    /**
     * Registers the guard. {@code refuse} gets the player and the destination of a pearl or chorus teleport and
     * returns true to cancel it (it tells the player why). Returns false when the event does not exist here.
     */
    static boolean install(Plugin plugin, Listener owner, BiPredicate<Player, Location> refuse) {
        Class<? extends Event> type;
        Method getTo;
        Method getCause;
        try {
            type = Class.forName(EVENT).asSubclass(Event.class);
            getTo = type.getMethod("getTo");
            getCause = type.getMethod("getCause");
        } catch (ClassNotFoundException | ClassCastException | NoSuchMethodException e) {
            return false;
        }
        Bukkit.getPluginManager().registerEvent(type, owner, EventPriority.HIGH, (listener, event) -> {
            if (!type.isInstance(event) || !(event instanceof EntityEvent entityEvent) || !(event instanceof Cancellable cancellable)
                || !(entityEvent.getEntity() instanceof Player player)) {
                return;
            }
            try {
                Object cause = getCause.invoke(event);
                if (cause != PlayerTeleportEvent.TeleportCause.ENDER_PEARL && cause != PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT) {
                    return;
                }
                if (getTo.invoke(event) instanceof Location to && refuse.test(player, to)) {
                    cancellable.setCancelled(true);
                }
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new EventException(e);
            }
        }, plugin, true);
        return true;
    }
}
