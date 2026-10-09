package net.siftvanilla.siftcore.feature.teams;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.siftvanilla.siftcore.core.config.Setting;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.scheduler.Scheduler;
import net.siftvanilla.siftcore.core.scheduler.Task;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Messenger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Teammate login alerts ({@code team-member-alerts}): a chat line when a teammate comes online, and for players who
 * want it when one goes offline. A login is looked at after the join delay (so a vanish applied on join is in effect)
 * and not told for a relog, right after a restart, or for a vanished member; a logout is told after the leave delay,
 * unless the member came back. Friends who already get the friends feature's alert for the same login are not told
 * twice, and ignored players are never announced. Decisions in {@link MemberAlertRules}; the timing is
 * {@code member-alerts} in teams.yml.
 * <p>
 * Decisions run on an async thread (memory and the settings cache only); each line is sent on the viewer's own
 * thread, where it is checked that they can see the member.
 */
final class LoginAlerts implements Listener {

    private final TeamRegistry registry;
    private final TeamPresence presence;
    private final PlayerSettings settings;
    private final Relations relations;
    private final Messenger messenger;
    private final Scheduler scheduler;
    private final Setting<TeamsSettings> config;
    private final Map<UUID, Long> lastLeave = new ConcurrentHashMap<>();
    private final Map<Long, Task> timers = new ConcurrentHashMap<>();
    private final AtomicLong nextTimer = new AtomicLong();
    private volatile long enabledAt = System.currentTimeMillis();

    LoginAlerts(TeamRegistry registry, TeamPresence presence, PlayerSettings settings, Relations relations, Messenger messenger,
                 Scheduler scheduler, Setting<TeamsSettings> config) {
        this.registry = registry;
        this.presence = presence;
        this.settings = settings;
        this.relations = relations;
        this.messenger = messenger;
        this.scheduler = scheduler;
        this.config = config;
    }

    /** The feature started now: the startup quiet counts from here. */
    void start(long now) {
        this.enabledAt = now;
    }

    /** Cancels every pending alert (disable). */
    void stop() {
        this.timers.values().forEach(Task::cancel);
        this.timers.clear();
        this.lastLeave.clear();
    }

    /** Runs {@code task} on an async thread after {@code delay}, unless {@link #stop()} ran first. */
    private void later(Runnable task, Duration delay) {
        long id = this.nextTimer.incrementAndGet();
        this.timers.put(id, Task.NONE);
        Task scheduled = this.scheduler.asyncLater(() -> {
            if (this.timers.remove(id) != null) {
                task.run();
            }
        }, delay.isZero() ? Duration.ofMillis(1) : delay);
        this.timers.replace(id, Task.NONE, scheduled);
    }

    // ------------------------------------------------------------------ logins

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (this.registry.of(id).isEmpty()) {
            return;
        }
        later(() -> joined(id), this.config.get().memberAlerts().joinDelay());
    }

    /** The join delay is over: tells each teammate who wants it. Async. */
    private void joined(UUID joiner) {
        Player player = Bukkit.getPlayer(joiner);
        Team team = this.registry.of(joiner).orElse(null);
        if (player == null || team == null) {
            return;
        }
        TeamsSettings.MemberAlerts timing = this.config.get().memberAlerts();
        long now = System.currentTimeMillis();
        if (!MemberAlertRules.announceJoin(this.presence.hidden(joiner), this.lastLeave.getOrDefault(joiner, 0L), now,
            timing.relogGrace().toMillis(), this.enabledAt, timing.startupQuiet().toMillis())) {
            return;
        }
        String announce = this.settings.encoded(joiner, MemberAlertRules.FRIENDS_ANNOUNCE);
        String name = player.getName();
        for (UUID member : team.memberIds()) {
            Player viewer = member.equals(joiner) ? null : Bukkit.getPlayer(member);
            if (viewer == null) {
                continue;
            }
            boolean friendsTold = MemberAlertRules.friendsTellJoin(this.relations.areFriends(member, joiner),
                this.settings.encoded(member, MemberAlertRules.FRIENDS_JOIN_ALERTS), announce,
                this.relations.friends().favourite(member, joiner));
            if (MemberAlertRules.tellJoin(this.settings.get(member, TeamPrefs.MEMBER_ALERTS), this.relations.ignores(member, joiner),
                friendsTold)) {
                this.scheduler.entity(viewer, () -> {
                    if (viewer.isOnline() && player.isOnline() && viewer.canSee(player) && this.registry.sameTeam(member, joiner)) {
                        this.messenger.send(viewer, TeamsMessages.MEMBER_ONLINE, Arg.text("name", name));
                    }
                }, null);
            }
        }
    }

    // ------------------------------------------------------------------ logouts

    /**
     * The member is leaving (their settings are still readable): remembers the time for the relog grace and schedules
     * the logout alert with what is true now (vanished, whether they let friends know).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        this.lastLeave.put(id, System.currentTimeMillis());
        Team team = this.registry.of(id).orElse(null);
        boolean vanished = this.presence.hidden(id);
        if (team == null || vanished) {
            return;
        }
        String announce = this.settings.encoded(id, MemberAlertRules.FRIENDS_ANNOUNCE);
        String name = player.getName();
        later(() -> left(id, name, announce), this.config.get().memberAlerts().leaveDelay());
    }

    /** The leave delay is over: tells each teammate who wants logouts, unless the member is back. Async. */
    private void left(UUID leaver, String name, String announce) {
        boolean back = Bukkit.getPlayer(leaver) != null;
        Team team = this.registry.of(leaver).orElse(null);
        if (back || team == null) {
            return;
        }
        for (UUID member : team.memberIds()) {
            Player viewer = member.equals(leaver) ? null : Bukkit.getPlayer(member);
            if (viewer == null) {
                continue;
            }
            boolean friendsTold = MemberAlertRules.friendsTellLeave(this.relations.areFriends(member, leaver),
                this.settings.encoded(member, MemberAlertRules.FRIENDS_LEAVE_ALERTS), announce);
            if (MemberAlertRules.tellLeave(this.settings.get(member, TeamPrefs.MEMBER_ALERTS), this.relations.ignores(member, leaver),
                friendsTold, false, false)) {
                this.scheduler.entity(viewer, () -> {
                    if (viewer.isOnline() && Bukkit.getPlayer(leaver) == null) {
                        this.messenger.send(viewer, TeamsMessages.MEMBER_OFFLINE, Arg.text("name", name));
                    }
                }, null);
            }
        }
    }

    /** Forgets relog times older than the grace (and at least a minute). */
    void prune() {
        long keep = Math.max(this.config.get().memberAlerts().relogGrace().toMillis(), 60_000L);
        long before = System.currentTimeMillis() - keep;
        this.lastLeave.values().removeIf(time -> time < before);
    }
}
