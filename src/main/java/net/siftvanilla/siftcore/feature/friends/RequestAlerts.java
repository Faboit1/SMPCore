package net.siftvanilla.siftcore.feature.friends;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Tells an online player about friend requests they can see. The first request in a quiet period is one chat line
 * with Accept and Deny links (plus the command in plain text for clients that can't click); more requests within the
 * batch window are added up into one "New friend requests: 3" line when the window closes, so alt accounts can't
 * flood anyone's chat. No dialog is embedded: inline dialog sessions run out and are capped per player, and older
 * clients can't open them. Requests from ignored players are never told. Thread-safe.
 */
final class RequestAlerts {

    private final Scheduler scheduler;
    private final Messenger messenger;
    private final Lang lang;
    private final Setting<FriendsSettings> settings;
    private final FriendPrefs prefs;
    private final FriendLinks links;
    private final Function<UUID, String> names;
    private final RequestBatcher batcher = new RequestBatcher();
    private final Map<UUID, Task> timers = new ConcurrentHashMap<>();
    private volatile Function<UUID, Map<UUID, Long>> incoming = player -> Map.of();

    RequestAlerts(Scheduler scheduler, Messenger messenger, Setting<FriendsSettings> settings, FriendPrefs prefs,
                  FriendLinks links, Function<UUID, String> names) {
        this.scheduler = scheduler;
        this.messenger = messenger;
        this.lang = messenger.lang();
        this.settings = settings;
        this.prefs = prefs;
        this.links = links;
        this.names = names;
    }

    /** Where the target's visible requests come from (the service, once it exists). */
    void incoming(Function<UUID, Map<UUID, Long>> source) {
        this.incoming = source;
    }

    /** A request from {@code sender} to {@code target} was stored as visible. Safe from any thread. */
    void sent(UUID target, UUID sender) {
        Player player = Bukkit.getPlayer(target);
        if (player == null || !this.prefs.requestAlerts(target) || this.links.ignores().ignores(target, sender)) {
            return;
        }
        Duration batch = this.settings.get().requestBatch();
        RequestBatcher.Decision decision = this.batcher.offer(target, sender, !batch.isZero());
        if (decision == RequestBatcher.Decision.SHOW_NOW) {
            String name = this.names.apply(sender);
            this.messenger.send(player, FriendsMessages.ALERT_REQUEST, Arg.text("name", name),
                Arg.component("accept", link(FriendsMessages.LINK_ACCEPT, this.lang.get(FriendsMessages.LINK_ACCEPT_HOVER,
                    Arg.text("name", name)), "/friend accept " + name)),
                Arg.component("deny", link(FriendsMessages.LINK_DENY, this.lang.get(FriendsMessages.LINK_DENY_HOVER,
                    Arg.text("name", name)), "/friend deny " + name)));
            if (!batch.isZero()) {
                schedule(target, batch);
            }
        }
    }

    private void schedule(UUID target, Duration batch) {
        Task previous = this.timers.put(target, this.scheduler.asyncLater(() -> close(target), batch));
        if (previous != null) {
            previous.cancel();
        }
    }

    /** The target's window closes: what it collected (and is still waiting and not ignored) is told in one line. */
    private void close(UUID target) {
        this.timers.remove(target);
        List<UUID> collected = this.batcher.close(target);
        if (collected.isEmpty()) {
            return;
        }
        Duration batch = this.settings.get().requestBatch();
        schedule(target, batch.isZero() ? Duration.ofSeconds(1) : batch);
        Player player = Bukkit.getPlayer(target);
        if (player == null) {
            return;
        }
        Map<UUID, Long> visible = this.incoming.apply(target);
        int count = 0;
        for (UUID sender : collected) {
            if (visible.containsKey(sender)) {
                count++;
            }
        }
        if (count > 0) {
            this.messenger.send(player, FriendsMessages.ALERT_REQUESTS, Arg.number("count", count), Arg.component("view", view()));
        }
    }

    /** The clickable "View" that runs {@code /friend requests}. */
    Component view() {
        return link(FriendsMessages.LINK_VIEW, this.lang.get(FriendsMessages.LINK_VIEW_HOVER), "/friend requests");
    }

    private Component link(net.siftvanilla.siftcore.core.text.MessageKey label, Component hover, String command) {
        return this.lang.get(label)
            .clickEvent(ClickEvent.runCommand(command))
            .hoverEvent(HoverEvent.showText(hover));
    }

    /** The player left: their window is dropped. */
    void forget(UUID player) {
        this.batcher.forget(player);
        Task timer = this.timers.remove(player);
        if (timer != null) {
            timer.cancel();
        }
    }

    /** Stops every timer (disable). */
    void stop() {
        this.timers.values().forEach(Task::cancel);
        this.timers.clear();
        this.batcher.clear();
    }

    int windows() {
        return this.batcher.size();
    }
}
