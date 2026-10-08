package net.siftvanilla.siftcore.feature.friends;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Messenger;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * One dependable signal when friends come online, and a short summary when you do.
 * <ul>
 *   <li>Login summary, a few seconds after joining once the player's friends are loaded: friends online (clickable),
 *       requests waiting, and friends made while away. Only lines with something to say, no sound.</li>
 *   <li>Join alerts: a join is looked at after the join delay (so a vanish applied on join is in effect), and not told
 *       at all for a relog, right after a restart, for a vanished player, or when the joiner turned it off. Each
 *       viewer's alerts are collected for one join delay and told in one chat line with clickable names; the notify
 *       sound plays only for a favourite.</li>
 *   <li>Leave alerts (off by default, never with a sound): after the leave delay, dropped when the friend came back;
 *       the name opens their profile like the join alert's.</li>
 * </ul>
 * Chat is used rather than the action bar because combat, sell and AFK messages overwrite the action bar.
 */
final class Presence {

    /** Names shown in a summary line before "and N more". */
    static final int SUMMARY_NAMES = 5;
    /** Names shown in a join alert before "and N more". */
    static final int ALERT_NAMES = 2;

    private final Scheduler scheduler;
    private final Messenger messenger;
    private final Lang lang;
    private final Setting<FriendsSettings> settings;
    private final FriendService service;
    private final FriendGraph graph;
    private final FriendPrefs prefs;
    private final FriendLinks links;
    private final RequestAlerts alerts;
    private final Logger logger;
    private volatile long enabledAt;
    private final PresenceRules.LastLeave lastLeave = new PresenceRules.LastLeave();
    private final PresenceRules.JoinBatches batches = new PresenceRules.JoinBatches();
    private final Map<Long, Task> timers = new ConcurrentHashMap<>();
    private final AtomicLong nextTimer = new AtomicLong();
    private final Map<UUID, Task> viewerFlushes = new ConcurrentHashMap<>();

    Presence(Scheduler scheduler, Messenger messenger, Setting<FriendsSettings> settings, FriendService service,
             RequestAlerts alerts, Logger logger) {
        this.scheduler = scheduler;
        this.messenger = messenger;
        this.lang = messenger.lang();
        this.settings = settings;
        this.service = service;
        this.graph = service.graph();
        this.prefs = service.prefs();
        this.links = service.links();
        this.alerts = alerts;
        this.logger = logger;
        this.enabledAt = System.currentTimeMillis();
    }

    /** The feature started now: the startup quiet counts from here. */
    void start(long now) {
        this.enabledAt = now;
    }

    // ------------------------------------------------------------------ joining

    /**
     * The player joined and their friends are loaded: schedules the login summary and the join alerts, counted
     * from the join itself. Safe from any thread.
     */
    void ready(Player player, long joinedAt) {
        FriendsSettings s = this.settings.get();
        long now = System.currentTimeMillis();
        long summaryIn = Math.max(0, joinedAt + s.summaryDelay().toMillis() - now);
        long joinIn = Math.max(0, joinedAt + s.joinDelay().toMillis() - now);
        later(() -> this.scheduler.entity(player, () -> summary(player), null), summaryIn);
        UUID id = player.getUniqueId();
        later(() -> evaluateJoin(id), joinIn);
    }

    /**
     * Runs {@code task} on an async thread after {@code millis}, unless {@link #stop()} ran first. The entry is put in
     * before the task is scheduled, so a task that runs at once still finds (and removes) it.
     */
    private void later(Runnable task, long millis) {
        long id = this.nextTimer.incrementAndGet();
        this.timers.put(id, Task.NONE);
        Task scheduled = this.scheduler.asyncLater(() -> {
            if (this.timers.remove(id) != null) {
                task.run();
            }
        }, Duration.ofMillis(Math.max(1, millis)));
        this.timers.replace(id, Task.NONE, scheduled);
    }

    /** The login summary. Runs on the player's thread (visibility is decided there). */
    void summary(Player player) {
        if (!player.isOnline()) {
            return;
        }
        UUID id = player.getUniqueId();
        FriendGraph.Node node = this.graph.loaded(id);
        if (node == null) {
            return;
        }
        List<UUID> online = new ArrayList<>();
        for (Map.Entry<UUID, FriendGraph.Edge> entry : node.friends().entrySet()) {
            Player friend = Bukkit.getPlayer(entry.getKey());
            if (friend != null && visible(player, friend)) {
                online.add(entry.getKey());
            }
        }
        boolean favouritesOn = this.settings.get().favouritesOn();
        online.sort(Comparator.<UUID>comparingInt(friend -> favouritesOn && node.friends().get(friend).favourite() ? 0 : 1)
            .thenComparing(friend -> this.service.name(friend).toLowerCase(Locale.ROOT)));
        int waiting = this.service.incoming(id).size();
        this.service.store().write(this.service.store().takeNotices(id)).whenComplete((notices, error) -> {
            if (error != null) {
                this.logger.log(Level.WARNING, "Could not read the friends " + player.getName() + " made while away", error);
            }
            if (!player.isOnline()) {
                if (notices != null && !notices.isEmpty()) {
                    // They left before seeing it: keep it for next time.
                    for (UUID friend : notices) {
                        this.service.store().write(this.service.store().markNotice(id, friend));
                    }
                }
                return;
            }
            if (!online.isEmpty()) {
                this.messenger.send(player, FriendsMessages.SUMMARY_ONLINE,
                    Arg.component("names", profileNames(online, SUMMARY_NAMES)));
            }
            if (waiting > 0) {
                this.messenger.send(player, FriendsMessages.SUMMARY_REQUESTS, Arg.number("count", waiting),
                    Arg.component("view", this.alerts.view()));
            }
            if (notices != null && !notices.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (UUID friend : notices) {
                    names.add(this.service.name(friend));
                }
                this.messenger.send(player, FriendsMessages.SUMMARY_NEW_FRIENDS, Arg.text("names", plainNames(names, SUMMARY_NAMES)));
            }
        });
    }

    /** Whether {@code friend} is shown as online to {@code viewer}. Call on the viewer's thread. */
    boolean visible(Player viewer, Player friend) {
        return friend.isOnline() && !this.links.vanish().vanished(friend.getUniqueId()) && viewer.canSee(friend);
    }

    /** The join delay is over: decides whether the join is told and collects it for each friend who wants it. */
    private void evaluateJoin(UUID joiner) {
        Player player = Bukkit.getPlayer(joiner);
        FriendGraph.Node node = this.graph.loaded(joiner);
        if (player == null || node == null) {
            return;
        }
        FriendsSettings s = this.settings.get();
        long now = System.currentTimeMillis();
        boolean announce = PresenceRules.announceJoin(this.prefs.announce(joiner), this.links.vanish().vanished(joiner),
            this.lastLeave.get(joiner), now, s.relogGrace().toMillis(), this.enabledAt, s.startupQuiet().toMillis());
        if (!announce) {
            return;
        }
        for (UUID friend : node.friends().keySet()) {
            Player viewer = Bukkit.getPlayer(friend);
            if (viewer == null) {
                continue;
            }
            FriendGraph.Edge edge = this.graph.edge(friend, joiner);
            boolean favourite = edge != null && edge.favourite() && s.favouritesOn();
            if (edge == null || !PresenceRules.viewerWants(this.prefs.joinAlerts(friend), favourite,
                this.links.ignores().ignores(friend, joiner))) {
                continue;
            }
            if (this.batches.add(friend, joiner, favourite)) {
                Task flush = this.scheduler.entityLater(viewer, () -> flush(viewer), () -> this.batches.forget(friend),
                    Math.max(1, s.joinDelay().toMillis() / 50));
                if (flush == Task.NONE) {
                    // The viewer is leaving (their entity is already retired): nothing will flush or forget the window
                    // just opened, and it would swallow every alert of their next session. Close it now.
                    this.batches.forget(friend);
                    continue;
                }
                Task previous = this.viewerFlushes.put(friend, flush);
                if (previous != null) {
                    previous.cancel();
                }
            }
        }
    }

    /** Tells one viewer the joins collected for them. Runs on the viewer's thread. */
    private void flush(Player viewer) {
        UUID id = viewer.getUniqueId();
        this.viewerFlushes.remove(id);
        PresenceRules.JoinBatches.Batch batch = this.batches.take(id);
        if (!viewer.isOnline()) {
            return;
        }
        List<UUID> shown = new ArrayList<>();
        boolean favourite = false;
        for (UUID joiner : batch.joiners()) {
            Player online = Bukkit.getPlayer(joiner);
            if (online != null && visible(viewer, online) && this.graph.friends(id, joiner)) {
                shown.add(joiner);
                favourite |= batch.favourites().contains(joiner);
            }
        }
        if (shown.isEmpty()) {
            return;
        }
        if (shown.size() == 1) {
            this.messenger.send(viewer, FriendsMessages.ALERT_ONLINE, Arg.component("name", profileName(shown.getFirst())));
        } else {
            this.messenger.send(viewer, FriendsMessages.ALERT_ONLINE_MANY, Arg.component("names", profileNames(shown, ALERT_NAMES)));
        }
        if (favourite) {
            this.messenger.feedback(viewer, Feedback.NOTIFY);
        }
    }

    // ------------------------------------------------------------------ leaving

    /**
     * The player is leaving (quit event, their settings are still readable): remembers the time for the relog grace
     * and schedules the leave alert with what is true now (their announce setting and whether they are vanished).
     */
    void left(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        this.lastLeave.record(id, now);
        this.batches.forget(id);
        Task flush = this.viewerFlushes.remove(id);
        if (flush != null) {
            flush.cancel();
        }
        this.alerts.forget(id);
        boolean announce = this.prefs.announce(id);
        boolean vanished = this.links.vanish().vanished(id);
        FriendGraph.Node node = this.graph.loaded(id);
        if (!announce || vanished || node == null || node.friends().isEmpty()) {
            return;
        }
        Set<UUID> friends = node.friends().keySet();
        later(() -> leaveAlert(id, friends, announce, vanished), this.settings.get().leaveDelay().toMillis());
    }

    private void leaveAlert(UUID leaver, Set<UUID> friends, boolean announce, boolean vanished) {
        boolean back = Bukkit.getPlayer(leaver) != null;
        if (!PresenceRules.announceLeave(announce, vanished, back)) {
            return;
        }
        for (UUID friend : friends) {
            Player viewer = Bukkit.getPlayer(friend);
            if (viewer != null && this.prefs.leaveAlerts(friend) && !this.links.ignores().ignores(friend, leaver)
                && this.graph.friends(friend, leaver)) {
                this.messenger.send(viewer, FriendsMessages.ALERT_OFFLINE, Arg.component("name", profileName(leaver)));
            }
        }
    }

    // ------------------------------------------------------------------ names

    /** A name that opens the player's profile when clicked. */
    Component profileName(UUID player) {
        String name = this.service.name(player);
        return Component.text(name, this.lang.style().palette().primary())
            .clickEvent(ClickEvent.runCommand("/profile " + name))
            .hoverEvent(HoverEvent.showText(this.lang.get(FriendsMessages.LINK_PROFILE_HOVER, Arg.text("name", name))));
    }

    /** "Alex", "Alex and Bob", "Alex, Bob and 2 more": every name clickable. */
    Component profileNames(List<UUID> players, int max) {
        List<Component> parts = new ArrayList<>();
        for (UUID player : players) {
            parts.add(profileName(player));
        }
        return NameList.join(parts, max, this.lang.plain(FriendsMessages.NAMES_AND),
            count -> this.lang.plain(FriendsMessages.NAMES_MORE, Arg.number("count", count)));
    }

    /** The same list as plain text. */
    String plainNames(List<String> names, int max) {
        List<Component> parts = new ArrayList<>();
        for (String name : names) {
            parts.add(Component.text(name));
        }
        return TextStyle.plain(NameList.join(parts, max, this.lang.plain(FriendsMessages.NAMES_AND),
            count -> this.lang.plain(FriendsMessages.NAMES_MORE, Arg.number("count", count))));
    }

    // ------------------------------------------------------------------ housekeeping

    /** Forgets relog times older than the grace. */
    void prune() {
        this.lastLeave.prune(System.currentTimeMillis() - Math.max(this.settings.get().relogGrace().toMillis(), 60_000L));
    }

    int lastLeaveSize() {
        return this.lastLeave.size();
    }

    /** Cancels every pending summary, alert and flush (disable). */
    void stop() {
        this.timers.values().forEach(Task::cancel);
        this.timers.clear();
        this.viewerFlushes.values().forEach(Task::cancel);
        this.viewerFlushes.clear();
        this.batches.clear();
        this.lastLeave.clear();
    }
}
