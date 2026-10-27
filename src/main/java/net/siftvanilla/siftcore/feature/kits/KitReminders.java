package net.siftvanilla.siftcore.feature.kits;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.MessageKey;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Tells players about their kits: on join which kits are ready (and that kit items are waiting), and while they play
 * the moment a kit's cooldown ends. Each online player has at most one timer, on their own thread, set for the
 * soonest cooldown that ends; nothing scans players on a timer. Each player picks how they are reminded (Kit
 * reminders: chat, above the hotbar, a title or not at all) and when (on join, when ready, or both); players who
 * want no reminder the moment a kit is ready get no timer at all. Changing either setting sets the timer again.
 */
final class KitReminders {

    /** Timers are set at most this far ahead; a longer cooldown is looked at again then. */
    private static final Duration HORIZON = Duration.ofHours(6);
    private static final long JOIN_DELAY_TICKS = 60L;

    private record Pending(Task task, Set<String> waiting) {
    }

    private final Services services;
    private final KitService kits;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    KitReminders(Services services, KitService kits) {
        this.services = services;
        this.kits = kits;
    }

    /** How the player wants kit reminders, or OFF when they get none (config, permission or their setting). */
    private AlertStyle style(Player player) {
        if (!this.kits.settings().reminders() || !player.hasPermission(KitsFeature.PERMISSION_USE)) {
            return AlertStyle.OFF;
        }
        return this.services.settings().get(player, KitPlayerSettings.REMINDERS);
    }

    private KitPlayerSettings.ReminderWhen when(Player player) {
        return this.services.settings().get(player, KitPlayerSettings.REMINDER_WHEN);
    }

    /** A reminder in the player's style: the clickable chat line, or the short one above the hotbar or as a title. */
    private void remind(Player player, AlertStyle style, MessageKey chat, MessageKey shorter, Arg... args) {
        this.services.messenger().alert(player, style, style == AlertStyle.CHAT ? chat : shorter, args);
    }

    /** Called when a player joins: the join reminder a moment later, then the timer. */
    void joined(Player player) {
        this.services.scheduler().entityLater(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            AlertStyle style = style(player);
            if (style != AlertStyle.OFF && when(player).onJoin()) {
                List<Kit> ready = this.kits.ready(player);
                boolean waiting = this.kits.waitingStacks(player.getUniqueId()) > 0;
                for (KitPlayerSettings.JoinLine line : KitPlayerSettings.joinLines(style, !ready.isEmpty(), waiting)) {
                    switch (line) {
                        case READY -> remind(player, style, KitsMessages.REMINDER_JOIN, KitsMessages.REMINDER_JOIN_SHORT,
                            Arg.component("kits", this.kits.text().names(ready)));
                        case WAITING -> remind(player, style, KitsMessages.REMINDER_WAITING, KitsMessages.REMINDER_WAITING_SHORT);
                        case READY_AND_WAITING -> this.services.messenger().alert(player, style,
                            KitsMessages.REMINDER_JOIN_WAITING_SHORT, Arg.component("kits", this.kits.text().names(ready)));
                    }
                }
            }
            schedule(player);
        }, null, JOIN_DELAY_TICKS);
    }

    /**
     * Sets the player's timer for the soonest cooldown that ends (replacing any timer); none when they want no
     * reminder the moment a kit is ready. Call on the player's thread.
     */
    void schedule(Player player) {
        UUID uuid = player.getUniqueId();
        cancel(uuid);
        if (!player.isOnline() || !KitPlayerSettings.timer(style(player), when(player))) {
            return;
        }
        Duration soonest = null;
        Set<String> waiting = new java.util.HashSet<>();
        for (Kit kit : this.kits.settings().kits()) {
            if (kit.cooldown().once() || !this.kits.permitted(player, kit)) {
                continue;
            }
            if (this.kits.status(player, kit) instanceof KitStatus.Waiting w) {
                waiting.add(kit.id());
                if (soonest == null || w.left().compareTo(soonest) < 0) {
                    soonest = w.left();
                }
            }
        }
        if (soonest == null) {
            return;
        }
        Duration delay = soonest.compareTo(HORIZON) > 0 ? HORIZON : soonest;
        long ticks = Math.max(20L, (delay.toMillis() + 49) / 50 + 20);
        Pending[] self = new Pending[1];
        Task task = this.services.scheduler().entityLater(player, () -> fire(player, self[0]),
            () -> this.pending.remove(uuid, self[0]), ticks);
        if (task == Task.NONE) {
            return;
        }
        self[0] = new Pending(task, Set.copyOf(waiting));
        this.pending.put(uuid, self[0]);
    }

    private void fire(Player player, Pending fired) {
        UUID uuid = player.getUniqueId();
        if (fired == null || !this.pending.remove(uuid, fired) || !player.isOnline()) {
            return;
        }
        AlertStyle style = style(player);
        if (style != AlertStyle.OFF && when(player).whenReady()) {
            List<Kit> nowReady = new ArrayList<>();
            for (Kit kit : this.kits.settings().kits()) {
                if (fired.waiting().contains(kit.id()) && this.kits.permitted(player, kit) && this.kits.status(player, kit).ready()) {
                    nowReady.add(kit);
                }
            }
            if (nowReady.size() == 1) {
                remind(player, style, KitsMessages.REMINDER_READY, KitsMessages.REMINDER_READY_SHORT,
                    Arg.text("name", nowReady.getFirst().name()));
            } else if (!nowReady.isEmpty()) {
                remind(player, style, KitsMessages.REMINDER_JOIN, KitsMessages.REMINDER_JOIN_SHORT,
                    Arg.component("kits", this.kits.text().names(nowReady)));
            }
        }
        schedule(player);
    }

    private void cancel(UUID uuid) {
        Pending previous = this.pending.remove(uuid);
        if (previous != null) {
            previous.task().cancel();
        }
    }

    /** Forgets a player who left. */
    void forget(UUID uuid) {
        cancel(uuid);
    }

    /** Sets every online player's timer again (after a reload changed cooldowns). */
    void rescheduleAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.services.scheduler().entity(player, () -> schedule(player), null);
        }
    }

    /** Stops every timer (disable). */
    void cancelAll() {
        for (UUID uuid : List.copyOf(this.pending.keySet())) {
            cancel(uuid);
        }
    }

    /** Players with a timer set. */
    int size() {
        return this.pending.size();
    }
}
